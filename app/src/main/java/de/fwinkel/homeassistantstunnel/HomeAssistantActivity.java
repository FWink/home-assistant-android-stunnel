package de.fwinkel.homeassistantstunnel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.Random;

import de.fwinkel.android_stunnel.PreSharedKey;
import de.fwinkel.android_stunnel.SSLCipher;
import de.fwinkel.android_stunnel.SSLVersion;
import de.fwinkel.android_stunnel.Stunnel;
import de.fwinkel.android_stunnel.StunnelBuilder;

public class HomeAssistantActivity extends Activity {

    protected static final String LOG_TAG = "HomeAssistant";

    private WebView webView;
    private ProgressBar progressBar;

    private RedirectConfig redirectConfig;
    private Stunnel stunnel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home_assistant);

        webView = findViewById(R.id.home_assistant_webview);
        progressBar = findViewById(R.id.prog_preparing);

        try {
            redirectConfig = loadRedirectConfig();
        } catch (Exception e) {
            e.printStackTrace();
        }
        if(!checkRedirectConfig(redirectConfig)) {
            onLoadConfigError();
            return;
        }

        setupWebView();
        new StunnelTask(this).execute(redirectConfig);
    }

    @Override
    protected void onDestroy() {
        if(stunnel != null) {
            try {
                stunnel.close();
            } catch (IOException e) {}
        }

        super.onDestroy();
    }

    /**
     * Returns a {@link RedirectConfig} which is retrieved from a config file embedded in the APK.
     * @return
     */
    private RedirectConfig loadRedirectConfig() throws IOException, JsonParseException {
        InputStream config = null;
        try {
            config = getAssets().open("config/redirect.json");
            RedirectConfig redirectConfig = new Gson().fromJson(new InputStreamReader(config), RedirectConfig.class);

            if(redirectConfig != null) {
                if(redirectConfig.localPort <= 0) {
                    //use a random port
                    Log.i(LOG_TAG, "Choosing a random local port. You should consider using a fixed local port. Otherwise storing your HomeAssistant credentials won't be possible");
                    redirectConfig = new RedirectConfig(
                            new Random().nextInt(65536 - 10000) + 10000,
                            redirectConfig.remoteAddress,
                            redirectConfig.remotePort,
                            redirectConfig.preSharedKey
                    );
                }
                if(redirectConfig.remotePort <= 0) {
                    //default to remote port 443
                    Log.i(LOG_TAG, "No remote port given. Connecting to 443");
                    redirectConfig = new RedirectConfig(
                            redirectConfig.localPort,
                            redirectConfig.remoteAddress,
                            443,
                            redirectConfig.preSharedKey
                    );
                }
            }

            return redirectConfig;
        }
        finally {
            if(config != null) {
                try {
                    config.close();
                }
                catch(IOException e) {
                    //ignore
                }
            }
        }
    }

    /**
     * Called when an error in {@link #loadRedirectConfig()} occurs. Shows an error message and aborts
     */
    private void onLoadConfigError() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(R.string.home_assistant_msg_config_missing_or_invalid);
        builder.setNeutralButton(R.string.btn_ok, null);

        builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                finish();
            }
        });

        builder.show();
    }

    /**
     * Checks if the given redirect config might be valid (each required value is given and looks valid).
     * @param config
     * @return
     */
    private boolean checkRedirectConfig(RedirectConfig config) {
        return config != null && config.remotePort < 65536 && config.localPort < 65536 &&
                config.remoteAddress != null && !config.remoteAddress.isEmpty() &&
                config.preSharedKey != null &&
                config.preSharedKey.getIdentity() != null && !config.preSharedKey.getIdentity().isEmpty() &&
                config.preSharedKey.getKey() != null && !config.preSharedKey.getKey().isEmpty();
    }

    /**
     * Sets up required WebView parameters for HomeAssistant (enabling javascript...)
     */
    private void setupWebView() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                //allow loading URLs. that seems to be required
                return false;
            }
        });

        //set debuggable
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                WebView.setWebContentsDebuggingEnabled(true);
            }
        }
    }

    @Override
    public void onBackPressed() {
        //TODO check if there is a closeable control active in the webview:
        // app-drawer opened
        // iron-dropdown focused
        // paper-dropdown-menu focused
        // vaadin-combo-box-overlay opened
        // vaadin-date-picker-overlay opened
        // ha-more-info-dialog style:display
        // more-info-settings style:display
        // not easily possible for now as they are hidden behind several layers of shadow-doms. would need to search recursively

        webView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE));

        //else no closeable:

//        if(webView.canGoBack())
//            webView.goBack();
//        else
//            super.onBackPressed();

    }

    /**
     * Called when an error occurred while setting up Stunnel: either the configuration could not be
     * loaded or there was an error starting the Stunnel process.
     */
    private void onStunnelError() {
        //TODO error
    }

    private void onStunnelOpened(Stunnel stunnel) {
        if(stunnel == null) {
            onStunnelError();
            return;
        }
        this.stunnel = stunnel;

        //hide the progress bar
        progressBar.setVisibility(View.GONE);

        //start the webview
        webView.loadUrl("http://localhost:" + redirectConfig.localPort);
    }

    private static class StunnelTask extends AsyncTask<RedirectConfig, Void, Stunnel> {

        private WeakReference<HomeAssistantActivity> activity;

        public StunnelTask(HomeAssistantActivity activity) {
            this.activity = new WeakReference<>(activity);
        }

        @Override
        protected Stunnel doInBackground(RedirectConfig... configs) {

            Context context = this.activity.get();
            if(context == null || configs.length == 0 || configs[0] == null)
                return null;

            RedirectConfig config = configs[0];

            try {
                return new StunnelBuilder(context)
                        .addService()
                            .client(true)
                            .acceptLocal(config.localPort)
                            .connect(config.remoteAddress, config.remotePort)
                            .delay(true)
                            .sslVersion(SSLVersion.TLSv1_2)
                            .ciphers(SSLCipher.PSK)
                            .pskSecrets(config.preSharedKey)
                            .apply()
                        .start();
            }
            catch(IOException e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        protected void onPostExecute(Stunnel stunnel) {

            HomeAssistantActivity activity = this.activity.get();
            if(activity == null) {
                try {
                    if(stunnel != null)
                        stunnel.close();
                } catch (IOException e) {}
                return;
            }

            activity.onStunnelOpened(stunnel);
        }
    }

    private static class RedirectConfig {
        public final int localPort;
        public final String remoteAddress;
        public final int remotePort;
        public final PreSharedKey preSharedKey;

        public RedirectConfig(int localPort, String remoteAddress, int remotePort, PreSharedKey preSharedKey) {
            this.localPort = localPort;
            this.remoteAddress = remoteAddress;
            this.remotePort = remotePort;
            this.preSharedKey = preSharedKey;
        }
    }
}
