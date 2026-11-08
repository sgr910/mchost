package com.termux.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * Tela unica do MCHost: Termux embutido + painel do servidor, tudo controlado por botoes.
 * Roda os comandos direto neste app (sem permissoes extras) e mostra o log ao vivo.
 */
public class MCHostActivity extends Activity {
    static final String DEFAULT_URL = "http://127.0.0.1:8080";
    static final String HOME = "/data/data/com.termux/files/home";
    static final String PREFIX = "/data/data/com.termux/files/usr";
    static final String BASH = PREFIX + "/bin/bash";
    static final String DEFAULT_DIR = HOME + "/mcpanel";
    static final int REQ_FILE = 42, REQ_PERM = 43;

    private WebView web;
    private TextView status;
    private LinearLayout setupRow;
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> fileCallback;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean pageLoaded = false;
    private boolean lastOk = false;
    private boolean ready = false;      // pacote-base do Termux instalado
    private volatile boolean busy = false;
    private volatile boolean alive = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("mchost", MODE_PRIVATE);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERM);
        }
        startStatusLoop();
        // Primeira abertura: instala o pacote-base do Termux (mostra o progresso dele) e depois o painel.
        TermuxInstaller.setupBootstrapIfNeeded(this, this::afterBootstrap);
    }

    private void afterBootstrap() {
        ready = true;
        if (!new File(DEFAULT_DIR, "app.py").exists()) {
            toast("Preparando o servidor pela primeira vez...");
            installAll(true);
        }
    }

    // ---------------------------------------------------------------- UI
    private Button bar(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1116);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(dp(6), dp(30), dp(6), 0);
        buttons.addView(bar("▶ Iniciar", v -> startServer()));
        buttons.addView(bar("■ Parar", v -> stopServer()));
        buttons.addView(bar("⟳", v -> reload()));
        Button more = bar("⋮", null);
        more.setOnClickListener(v -> showMenu(v));
        buttons.addView(more);
        root.addView(buttons);

        status = new TextView(this);
        status.setTextColor(0xFF8591A3);
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(2), 0, dp(4));
        status.setText("verificando...");
        root.addView(status);

        setupRow = new LinearLayout(this);
        setupRow.setOrientation(LinearLayout.HORIZONTAL);
        setupRow.setPadding(dp(6), 0, dp(6), dp(4));
        setupRow.addView(bar("Instalar / reparar", v -> installAll(false)));
        setupRow.addView(bar("Ver terminal", v -> openTerminal()));
        root.addView(setupRow);

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        setupWebView();
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setBackgroundColor(0xFF0E1116);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                autoLogin(0);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest req) {
                Uri u = req.getUrl();
                Uri base = Uri.parse(prefs.getString("url", DEFAULT_URL));
                if (u.getHost() != null && u.getHost().equals(base.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u)); // links externos no navegador
                } catch (Exception ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    toast("Não consegui abrir o seletor de arquivos");
                    return false;
                }
                return true;
            }
        });
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) r.addRequestHeader("Cookie", cookie);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                String name = URLUtil.guessFileName(url, cd, mime);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
                toast("Baixando " + name + " (pasta Downloads)");
            } catch (Exception e) {
                toast("Falha no download: " + e.getMessage());
            }
        });
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Bateria: sem restrições");
        m.getMenu().add(0, 2, 1, "Abrir terminal");
        m.getMenu().add(0, 3, 2, "Configurar caminho/endereço");
        m.getMenu().add(0, 5, 3, "Atualizar painel");
        m.getMenu().add(0, 6, 4, "Mostrar senha do painel");
        m.getMenu().add(0, 4, 5, "Ajuda");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: askIgnoreBattery(); break;
                case 2: openTerminal(); break;
                case 3: configDialog(); break;
                case 4: helpDialog(); break;
                case 5: updatePanel(); break;
                case 6: passwordDialog(); break;
            }
            return true;
        });
        m.show();
    }

    private void openTerminal() {
        startActivity(new Intent(this, TermuxActivity.class));
    }

    // ------------------------------------------------------------ ações
    private void startServer() {
        if (!ready || !new File(DEFAULT_DIR, "app.py").exists()) {
            toast("O painel ainda não foi instalado. Toque em \"Instalar / reparar\".");
            return;
        }
        runQuiet("bash " + sh(dirOf() + "/termux/start.sh") + " " + sh(panelPassword()));
        startForegroundServiceCompat();
        toast("Iniciando o painel...");
        pageLoaded = false;
        waitAndLoad(0);
    }

    private void stopServer() {
        if (!ready) return;
        runQuiet("bash " + sh(dirOf() + "/termux/stop.sh"));
        toast("Parando e salvando o mundo... pode levar até 1–2 min");
    }

    private String dirOf() {
        return prefs.getString("dir", DEFAULT_DIR);
    }

    private static String sh(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Senha do painel gerada pelo app (uma vez); o app passa ao painel e faz o login sozinho. */
    private String panelPassword() {
        String pw = prefs.getString("pw", null);
        if (pw == null) {
            String chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
            SecureRandom r = new SecureRandom();
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < 16; k++) b.append(chars.charAt(r.nextInt(chars.length())));
            pw = b.toString();
            prefs.edit().putString("pw", pw).apply();
        }
        return pw;
    }

    private void autoLogin(int attempt) {
        if (!alive || attempt > 10 || web == null) return;
        String js = "(function(){var l=document.getElementById('login');"
                + "if(l&&l.classList.contains('on')){document.getElementById('pw').value='" + panelPassword() + "';"
                + "doLogin({preventDefault:function(){}});return 'tentou';}return l?'ok':'sem-painel';})()";
        web.evaluateJavascript(js, null);
        ui.postDelayed(() -> autoLogin(attempt + 1), 700);
    }

    private void reload() {
        pageLoaded = false;
        loadPanel();
    }

    private void waitAndLoad(int attempt) {
        if (!alive || attempt > 40) return;
        new Thread(() -> {
            boolean ok = ping(prefs.getString("url", DEFAULT_URL));
            ui.post(() -> {
                if (ok) loadPanel();
                else ui.postDelayed(() -> waitAndLoad(attempt + 1), 1500);
            });
        }).start();
    }

    private void loadPanel() {
        web.loadUrl(prefs.getString("url", DEFAULT_URL));
        pageLoaded = true;
    }

    private void startForegroundServiceCompat() {
        try {
            startForegroundService(new Intent(this, MCHostKeepAlive.class));
        } catch (Exception e) {
            toast("Não consegui iniciar o serviço em segundo plano: " + e.getMessage());
        }
    }

    // ------------------------------------------------- execução de comandos
    private Process spawn(String script) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(BASH, "-c", script);
        pb.directory(new File(HOME));
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.clear();
        HashMap<String, String> te = new TermuxShellEnvironment().getEnvironment(this, false);
        env.putAll(te);
        env.put("HOME", HOME);
        env.put("PREFIX", PREFIX);
        if (!env.containsKey("PATH") || !env.get("PATH").contains(PREFIX + "/bin")) {
            env.put("PATH", PREFIX + "/bin");
        }
        env.put("DEBIAN_FRONTEND", "noninteractive");
        return pb.start();
    }

    /** Roda um comando curto sem janela (iniciar/parar o painel). */
    private void runQuiet(String script) {
        new Thread(() -> {
            try {
                Process p = spawn(script);
                p.getOutputStream().close();
                InputStream in = p.getInputStream();
                byte[] b = new byte[4096];
                while (in.read(b) > 0) { /* descarta */ }
                p.waitFor();
            } catch (Exception e) {
                ui.post(() -> toast("Falha ao executar: " + e.getMessage()));
            }
        }, "mchost-quiet").start();
    }

    /** Roda um script mostrando a saída real, ao vivo, numa janela. */
    private void runLive(String title, String script, byte[] stdin, Runnable onSuccess) {
        if (!ready) {
            toast("Aguarde a preparação do Termux terminar.");
            return;
        }
        if (busy) {
            toast("Já tem uma instalação em andamento.");
            return;
        }
        busy = true;
        final TextView logv = new TextView(this);
        final ScrollView sv = new ScrollView(this);
        logv.setTextSize(10);
        logv.setTypeface(android.graphics.Typeface.MONOSPACE);
        logv.setTextColor(0xFFCFD8E3);
        logv.setPadding(dp(10), dp(8), dp(10), dp(8));
        logv.setText("(saída real do instalador)\n");
        sv.setBackgroundColor(0xFF0E1116);
        sv.addView(logv);
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle(title).setView(sv)
                .setNegativeButton("Fechar", null)
                .setNeutralButton("Copiar", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("log", logv.getText()));
                    toast("Log copiado");
                }).show();
        new Thread(() -> {
            int code = -1;
            try {
                Process p = spawn(script);
                final OutputStream os = p.getOutputStream();
                new Thread(() -> {
                    try {
                        if (stdin != null) os.write(stdin);
                        os.close();
                    } catch (Exception ignored) { }
                }).start();
                InputStreamReader r = new InputStreamReader(p.getInputStream(), "UTF-8");
                char[] buf = new char[2048];
                int n;
                while ((n = r.read(buf)) > 0) {
                    final String t = new String(buf, 0, n)
                            .replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "").replace("\r\n", "\n").replace('\r', '\n');
                    ui.post(() -> {
                        logv.append(t);
                        CharSequence cs = logv.getText();
                        if (cs.length() > 24000) logv.setText(cs.subSequence(cs.length() - 16000, cs.length()));
                        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
                    });
                }
                code = p.waitFor();
            } catch (Exception e) {
                final String msg = "\n[erro] " + e.getMessage() + "\n";
                ui.post(() -> logv.append(msg));
            }
            busy = false;
            final int fc = code;
            ui.post(() -> {
                logv.append("\n=== terminou (código " + fc + ") ===\n");
                sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
                if (fc == 0 && onSuccess != null) onSuccess.run();
            });
        }, "mchost-live").start();
    }

    private byte[] readAsset(String name) throws Exception {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** Instala Python, Java e o painel embutido. Com thenStart, liga o painel ao terminar. */
    private void installAll(boolean thenStart) {
        try {
            byte[] pack = readAsset("mcpanel.pack");
            String script = "set -e\ncd ~\ntar xz -C ~\n"
                    + "bash ~/mcpanel/termux/install.sh\n"
                    + "echo\necho '=== Tudo pronto! ==='\n";
            runLive("Instalando (alguns minutos)", script, pack, thenStart ? this::startServer : null);
        } catch (Exception e) {
            toast("Falha ao preparar a instalação: " + e.getMessage());
        }
    }

    /** Atualiza só os arquivos do painel (rápido); mundo e mods ficam. */
    private void updatePanel() {
        try {
            byte[] pack = readAsset("mcpanel.pack");
            String script = "set -e\ncd ~\ntar xz -C ~\nchmod +x ~/mcpanel/termux/*.sh\n"
                    + "echo '=== Painel atualizado. Pare e inicie o servidor para valer. ==='\n";
            runLive("Atualizando o painel", script, pack, null);
        } catch (Exception e) {
            toast("Falha ao atualizar: " + e.getMessage());
        }
    }

    private void askIgnoreBattery() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast("O app já está sem restrições de bateria.");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // ----------------------------------------------------------- diálogos
    private void passwordDialog() {
        final String pw = panelPassword();
        new AlertDialog.Builder(this)
                .setTitle("Senha do painel")
                .setMessage(pw + "\n\nDentro do app o login é automático. Para abrir no PC use "
                        + "http://IP-DO-CELULAR:8080 (o IP aparece no Console, em \"Rede local\").")
                .setPositiveButton("Copiar", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("senha", pw));
                    toast("Senha copiada");
                })
                .setNegativeButton("Fechar", null).show();
    }

    private void configDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        TextView l1 = new TextView(this);
        l1.setText("Pasta do painel");
        EditText dir = new EditText(this);
        dir.setSingleLine(true);
        dir.setText(prefs.getString("dir", DEFAULT_DIR));
        TextView l2 = new TextView(this);
        l2.setText("Endereço do painel");
        EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setText(prefs.getString("url", DEFAULT_URL));
        box.addView(l1);
        box.addView(dir);
        box.addView(l2);
        box.addView(url);
        new AlertDialog.Builder(this)
                .setTitle("Configuração")
                .setView(box)
                .setPositiveButton("Salvar", (d, w) -> {
                    prefs.edit()
                            .putString("dir", dir.getText().toString().trim())
                            .putString("url", url.getText().toString().trim())
                            .apply();
                    toast("Salvo");
                })
                .setNeutralButton("Padrão", (d, w) -> {
                    prefs.edit().remove("dir").remove("url").apply();
                    toast("Valores padrão restaurados");
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private void helpDialog() {
        new AlertDialog.Builder(this)
                .setTitle("MCHost")
                .setMessage("Servidor de Minecraft completo dentro deste app.\n\n"
                        + "Iniciar: liga o painel e mantém o celular acordado.\n"
                        + "Parar: salva o mundo e desliga tudo.\n"
                        + "Instalar / reparar: baixa Python, Java e o painel de novo.\n\n"
                        + "No PC, na mesma rede Wi-Fi, use o endereço \"Rede local\" mostrado no Console do painel.")
                .setPositiveButton("OK", null).show();
    }

    // ------------------------------------------------------------ status
    private void startStatusLoop() {
        new Thread(() -> {
            while (alive) {
                boolean ok = ping(prefs.getString("url", DEFAULT_URL));
                ui.post(() -> {
                    if (setupRow != null) setupRow.setVisibility(ok ? View.GONE : View.VISIBLE);
                    if (status == null) return;
                    status.setText(ok ? "● painel online" : "○ painel offline — toque em Iniciar");
                    status.setTextColor(ok ? 0xFF22C55E : 0xFFEF4444);
                    if (ok && !lastOk) pageLoaded = false;
                    lastOk = ok;
                    if (ok && !pageLoaded) loadPanel();
                });
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "mchost-status").start();
    }

    static boolean ping(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            c.setRequestMethod("GET");
            c.getResponseCode();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------ outros
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCallback = null;
        } else {
            super.onActivityResult(req, res, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        super.onDestroy();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
