package bg.datecs.webprint;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.datecs.printer.Printer;
import com.datecs.printer.PrinterInformation;
import com.datecs.printer.ProtocolAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;

import bg.datecs.webprint.connectivity.BluetoothConnector;
import bg.datecs.webprint.connectivity.BluetoothSppConnector;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "WebPrint";

    private static final int REQUEST_ENABLE_BT = 1;

    private static final int REQUEST_SETTINGS = 2;

    private static final String PREF_URL = "url";

    private static final String DEFAULT_URL = "http://www.datecs.bg";

    private WebView mWebView;

    private Bitmap mBitmap;

    private final Handler mHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.enableSlowWholeDocumentDraw();
        setContentView(R.layout.activity_main);

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        String page = prefs.getString(PREF_URL, DEFAULT_URL);

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            if ("text/plain".equals(type)) {
                String url = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (url != null && URLUtil.isValidUrl(url) ) {
                    prefs.edit().putString(PREF_URL, url).apply();
                    page = url;
                }
            }
        }

        final Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        final FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                print();
            }
        });

        final ProgressBar progressBar = (ProgressBar) findViewById(R.id.progress);

        mWebView = (WebView)findViewById(R.id.webview);

        WebSettings webSettings = mWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "onPageFinished()");
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mBitmap = takePicture(mWebView);
                    }
                }, 1000);
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            public void onProgressChanged(WebView view, int progress) {
                if (progress == 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                }
            }
        });

        mWebView.loadUrl(page);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onBackPressed() {
        if (mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                print();
            }
        } else if (requestCode == REQUEST_SETTINGS) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            String url = prefs.getString(PREF_URL, DEFAULT_URL);
            if (!mWebView.getUrl().equals(url)) {
                mWebView.loadUrl(url);
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private Bitmap takePicture(WebView webView) {
        int w = webView.getWidth();
        int h = webView.getContentHeight();

        Log.d(TAG, "Create bitmap " + w + "x" + h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);

        Log.d(TAG, "Draw view");
        Canvas c = new Canvas(b);
        webView.draw(c);

        return b;
    }

    private void print() {
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null) {
            return;
        }

        if (!btAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            return;
        }

        Set<BluetoothDevice> devices = btAdapter.getBondedDevices();

        if (devices.size() == 0) {
            Toast.makeText(this, "No bluetooth device found", Toast.LENGTH_SHORT).show();
            return;
        }

        selectPrinter(devices.toArray(new BluetoothDevice[devices.size()]));
    }

    private void selectPrinter(final BluetoothDevice[] devices) {
        AlertDialog.Builder builderSingle = new AlertDialog.Builder(this);
        builderSingle.setIcon(R.mipmap.ic_launcher);
        builderSingle.setTitle(R.string.select_printer);

        final ArrayAdapter<String> arrayAdapter = new ArrayAdapter<String>(this, android.R.layout.select_dialog_singlechoice);
        for (BluetoothDevice device: devices) {
            arrayAdapter.add(device.getName());
        }

        builderSingle.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });

        builderSingle.setAdapter(arrayAdapter, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                printImage(devices[which], mBitmap);
            }
        });
        builderSingle.show();
    }

    private void printImage(final BluetoothDevice device, final Bitmap bitmap) {
        final Context context = this;
        final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();

        final ProgressDialog dialog = new ProgressDialog(context);
        dialog.setMessage(getString(R.string.please_wait));
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                BluetoothConnector connector = new BluetoothSppConnector(context, btAdapter, device);
                try {
                    connector.connect();

                    InputStream inputStream = connector.getInputStream();
                    OutputStream outputStream = connector.getOutputStream();
                    ProtocolAdapter adapter = new ProtocolAdapter(inputStream, outputStream);
                    Printer printer;
                    if (adapter.isProtocolEnabled()) {
                        ProtocolAdapter.Channel channel = adapter.getChannel(ProtocolAdapter
                                .CHANNEL_PRINTER);
                        printer = new Printer(channel.getInputStream(), channel.getOutputStream());
                    } else {
                        printer = new Printer(adapter.getRawInputStream(), adapter
                                .getRawOutputStream());
                    }

                    PrinterInformation information = printer.getInformation();
                    int newWidth = information.getMaxPageWidth();
                    int newHeight = bitmap.getHeight() * newWidth / bitmap.getWidth();
                    Bitmap scaledBitmap = Bitmap.createScaledBitmap(
                            bitmap, newWidth, newHeight, false);
                    final int[] argb = new int[newWidth * newHeight];
                    scaledBitmap.getPixels(argb, 0, newWidth, 0, 0, newWidth, newHeight);

                    Log.d(TAG, "Original bitmap: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                    Log.d(TAG, "Scaled bitmap: " + newWidth + "x" + newHeight);
                    scaledBitmap.recycle();

                    printer.reset();
                    printer.printImage(argb, newWidth, newHeight, Printer.ALIGN_CENTER, true);
                    printer.feedPaper(110);
                    printer.flush();
                    printer.close();
                } catch (IOException e) {
                    e.printStackTrace();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) {
                                return;
                            }

                            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                            builder.setIcon(R.mipmap.ic_launcher);
                            builder.setTitle(R.string.retry);
                            builder.setNegativeButton(android.R.string.no, new
                                    DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                }
                            });
                            builder.setPositiveButton(android.R.string.yes, new
                                    DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            dialog.dismiss();
                                            print();
                                        }
                                    });
                            builder.show();
                        }
                    });
                } finally {
                    try {
                        connector.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    dialog.dismiss();
                }
            }
        }).start();
    }
}

