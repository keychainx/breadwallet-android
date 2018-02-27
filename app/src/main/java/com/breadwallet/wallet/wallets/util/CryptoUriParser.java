package com.breadwallet.wallet.wallets.util;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.breadwallet.BuildConfig;
import com.breadwallet.R;
import com.breadwallet.core.BRCoreAddress;
import com.breadwallet.core.BRCoreKey;
import com.breadwallet.core.BRCoreTransaction;
import com.breadwallet.presenter.customviews.BRDialogView;
import com.breadwallet.presenter.entities.CryptoRequest;
import com.breadwallet.tools.animation.BRAnimator;
import com.breadwallet.tools.animation.BRDialog;
import com.breadwallet.tools.manager.BRClipboardManager;
import com.breadwallet.tools.manager.BREventManager;
import com.breadwallet.tools.manager.BRReportsManager;
import com.breadwallet.tools.manager.SendManager;
import com.breadwallet.tools.threads.ImportPrivKeyTask;
import com.breadwallet.tools.threads.PaymentProtocolTask;
import com.breadwallet.tools.util.BRConstants;
import com.breadwallet.tools.util.Utils;
import com.breadwallet.wallet.WalletsMaster;
import com.breadwallet.wallet.abstracts.BaseWalletManager;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;


/**
 * BreadWallet
 * <p/>
 * Created by Mihail Gutan <mihail@breadwallet.com> on 10/19/15.
 * Copyright (c) 2016 breadwallet LLC
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

public class CryptoUriParser {
    private static final String TAG = CryptoUriParser.class.getName();
    private static final Object lockObject = new Object();

    public static synchronized boolean processRequest(Context app, String url, BaseWalletManager walletManager) {
        if (url == null) {
            Log.e(TAG, "processRequest: url is null");
            return false;
        }

        if (ImportPrivKeyTask.trySweepWallet(app, url, walletManager)) return true;

        CryptoRequest requestObject = parseRequest(app, url);

        if (requestObject == null) {
            if (app != null) {
                BRDialog.showCustomDialog(app, app.getString(R.string.JailbreakWarnings_title),
                        app.getString(R.string.Send_invalidAddressTitle), app.getString(R.string.Button_ok), null, new BRDialogView.BROnClickListener() {
                            @Override
                            public void onClick(BRDialogView brDialogView) {
                                brDialogView.dismissWithAnimation();
                            }
                        }, null, null, 0);
            }
            return false;
        }
        if (requestObject.isPaymentProtocol()) {
            return tryPaymentRequest(requestObject);
        } else if (requestObject.address != null) {
            return tryCryptoUrl(requestObject, app);
        } else {
            if (app != null) {
                BRDialog.showCustomDialog(app, app.getString(R.string.JailbreakWarnings_title),
                        app.getString(R.string.Send_remoteRequestError), app.getString(R.string.Button_ok), null, new BRDialogView.BROnClickListener() {
                            @Override
                            public void onClick(BRDialogView brDialogView) {
                                brDialogView.dismissWithAnimation();
                            }
                        }, null, null, 0);
            }
            return false;
        }
    }

    private static void pushUrlEvent(Uri u) {
        Map<String, String> attr = new HashMap<>();
        attr.put("scheme", u == null ? "null" : u.getScheme());
        attr.put("host", u == null ? "null" : u.getHost());
        attr.put("path", u == null ? "null" : u.getPath());
        BREventManager.getInstance().pushEvent("send.handleURL", attr);
    }

    public static boolean isCryptoUrl(Context app, String url) {
        if (Utils.isNullOrEmpty(url)) return false;
        if (BRCoreKey.isValidBitcoinBIP38Key(url) || BRCoreKey.isValidBitcoinPrivateKey(url))
            return true;
        else
            Log.e(TAG, "isCryptoUrl: not a private key");
        CryptoRequest requestObject = parseRequest(app, url);
        // return true if the request is valid url and has param: r or param: address
        // return true if it is a valid bitcoinPrivKey
        return (requestObject != null && (requestObject.isPaymentProtocol() || requestObject.hasAddress()));
    }


    public static CryptoRequest parseRequest(Context app, String str) {
        if (str == null || str.isEmpty()) return null;
        CryptoRequest obj = new CryptoRequest();

        String tmp = str.trim().replaceAll("\n", "").replaceAll(" ", "%20");

        Uri u = Uri.parse(tmp);
        String scheme = u.getScheme();
        BaseWalletManager wm = WalletsMaster.getInstance(app).getCurrentWallet(app);

        if (scheme == null) {
            scheme = wm.getScheme(app);
            obj.iso = wm.getIso(app);
        }

        String schemeSpecific = u.getSchemeSpecificPart();
        if (schemeSpecific.startsWith("//")) {
            // Fix invalid bitcoin uri
            schemeSpecific = schemeSpecific.substring(2);
        }

        u = Uri.parse(scheme + "://" + schemeSpecific);

        pushUrlEvent(u);

        String host = u.getHost();
        if (host != null) {
            String addrs = host.trim();

            if (new BRCoreAddress(addrs).isValid()) {
                obj.address = addrs;
            }
        }
        String query = u.getQuery();
        if (query == null) return obj;
        String[] params = query.split("&");
        for (String s : params) {
            String[] keyValue = s.split("=", 2);
            if (keyValue.length != 2)
                continue;
            if (keyValue[0].trim().equals("amount")) {
                try {
                    BigDecimal bigDecimal = new BigDecimal(keyValue[1].trim());
                    obj.amount = bigDecimal.multiply(new BigDecimal("100000000"));
                } catch (NumberFormatException e) {
                    e.printStackTrace();
                }
            } else if (keyValue[0].trim().equals("label")) {
                obj.label = keyValue[1].trim();
            } else if (keyValue[0].trim().equals("message")) {
                obj.message = keyValue[1].trim();
            } else if (keyValue[0].trim().startsWith("req")) {
                obj.req = keyValue[1].trim();
            } else if (keyValue[0].trim().startsWith("r")) {
                obj.r = keyValue[1].trim();
            }
        }
        return obj;
    }

    private static boolean tryPaymentRequest(CryptoRequest requestObject) {
        String theURL = null;
        String url = requestObject.r;
        synchronized (lockObject) {
            try {
                theURL = URLDecoder.decode(url, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
                return false;
            }
            new PaymentProtocolTask().execute(theURL, requestObject.label);
        }
        return true;
    }

    private static boolean tryCryptoUrl(final CryptoRequest requestObject, final Context ctx) {
        final Activity app;
        if (ctx instanceof Activity) {
            app = (Activity) ctx;
        } else {
            Log.e(TAG, "tryCryptoUrl: " + "app isn't activity: " + ctx.getClass().getSimpleName());
            BRReportsManager.reportBug(new NullPointerException("app isn't activity: " + ctx.getClass().getSimpleName()));
            return false;
        }
        if (requestObject == null || requestObject.address == null || requestObject.address.isEmpty())
            return false;
        BaseWalletManager wallet = WalletsMaster.getInstance(app).getCurrentWallet(app);
        if (requestObject.iso != null && !requestObject.iso.equalsIgnoreCase(wallet.getIso(ctx))) {

            BRDialog.showCustomDialog(app, app.getString(R.string.Alert_error), "Not a valid " + wallet.getName(ctx) + " address", app.getString(R.string.AccessibilityLabels_close), null, new BRDialogView.BROnClickListener() {
                @Override
                public void onClick(BRDialogView brDialogView) {
                    brDialogView.dismiss();
                }
            }, null, null, 0);
            return true; //true since it's a crypto url but different iso than the currently chosen one
        }
//        String amount = requestObject.amount;

        if (requestObject.amount == null || requestObject.amount.doubleValue() == 0) {
            app.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    BRAnimator.showSendFragment(app, requestObject);
                }
            });
        } else {
            BRAnimator.killAllFragments(app);
            BRCoreTransaction tx = wallet.getWallet().createTransaction(requestObject.amount.longValue(), new BRCoreAddress(requestObject.address));
            if (tx == null) {
                BRDialog.showCustomDialog(app, app.getString(R.string.Alert_error), "Insufficient amount for transaction", app.getString(R.string.AccessibilityLabels_close), null, new BRDialogView.BROnClickListener() {
                    @Override
                    public void onClick(BRDialogView brDialogView) {
                        brDialogView.dismiss();
                    }
                }, null, null, 0);
                return true;
            }
            requestObject.tx = tx;
            SendManager.sendTransaction(app, requestObject, wallet);
        }

        return true;

    }

    public static String createBitcoinUrl(String address, long satoshiAmount, String label, String message, String rURL) {

        Uri.Builder builder = new Uri.Builder();
        builder = builder.scheme("bitcoin");
        if (address != null && !address.isEmpty())
            builder = builder.appendPath(address);
        if (satoshiAmount != 0)
            builder = builder.appendQueryParameter("amount", new BigDecimal(satoshiAmount).divide(new BigDecimal(100000000), 8, BRConstants.ROUNDING_MODE).toPlainString());
        if (label != null && !label.isEmpty())
            builder = builder.appendQueryParameter("label", label);
        if (message != null && !message.isEmpty())
            builder = builder.appendQueryParameter("message", message);
        if (rURL != null && !rURL.isEmpty())
            builder = builder.appendQueryParameter("r", rURL);

        return builder.build().toString().replaceFirst("/", "");

    }


}
