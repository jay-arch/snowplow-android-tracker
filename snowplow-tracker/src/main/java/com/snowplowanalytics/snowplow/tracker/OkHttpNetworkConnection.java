package com.snowplowanalytics.snowplow.tracker;

import android.net.Uri;
import android.os.Build;
import android.support.annotation.NonNull;

import com.snowplowanalytics.snowplow.tracker.constants.TrackerConstants;
import com.snowplowanalytics.snowplow.tracker.emitter.HttpMethod;
import com.snowplowanalytics.snowplow.tracker.emitter.RequestResult;
import com.snowplowanalytics.snowplow.tracker.emitter.RequestSecurity;
import com.snowplowanalytics.snowplow.tracker.emitter.TLSArguments;
import com.snowplowanalytics.snowplow.tracker.emitter.TLSVersion;
import com.snowplowanalytics.snowplow.tracker.networkconnection.Request;
import com.snowplowanalytics.snowplow.tracker.utils.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;

import static com.snowplowanalytics.snowplow.tracker.emitter.HttpMethod.GET;
import static com.snowplowanalytics.snowplow.tracker.emitter.HttpMethod.POST;
import static com.snowplowanalytics.snowplow.tracker.emitter.RequestSecurity.HTTP;

/**
 * Components in charge to send events to the collector.
 * It uses OkHttp as Http client.
 */
public class OkHttpNetworkConnection implements NetworkConnection {
    private final String TAG = OkHttpNetworkConnection.class.getSimpleName();

    private static final String DEFAULT_USER_AGENT = String.format("snowplow/%s android/%s", BuildConfig.TRACKER_LABEL, Build.VERSION.RELEASE);
    private final MediaType JSON = MediaType.parse(TrackerConstants.POST_CONTENT_TYPE);

    private final String uri;
    private final RequestSecurity protocol;
    private final HttpMethod httpMethod;
    private final int emitTimeout;
    private final String customPostPath;

    private OkHttpClient client;
    private Uri.Builder uriBuilder;

    /**
     * Builder for the OkHttpNetworkConnection.
     */
    public static class OkHttpNetworkConnectionBuilder {
        final String uri; // Required
        HttpMethod httpMethod = POST; // Optional
        RequestSecurity requestSecurity = RequestSecurity.HTTP; // Optional
        EnumSet<TLSVersion> tlsVersions = EnumSet.of(TLSVersion.TLSv1_2); // Optional
        private int emitTimeout = 5; // Optional
        OkHttpClient client = null; //Optional
        String customPostPath = null; //Optional

        /**
         * @param uri The uri of the collector
         */
        public OkHttpNetworkConnectionBuilder(String uri) {
            this.uri = uri;
        }

        /**
         * @param httpMethod The method by which requests are emitted
         * @return itself
         */
        public OkHttpNetworkConnectionBuilder method(HttpMethod httpMethod) {
            this.httpMethod = httpMethod;
            return this;
        }

        /**
         * @param requestSecurity the security chosen for requests
         * @return itself
         */
        public OkHttpNetworkConnectionBuilder security(RequestSecurity requestSecurity) {
            this.requestSecurity = requestSecurity;
            return this;
        }

        /**
         * @param version the TLS version allowed for requests
         * @return itself
         */
        public OkHttpNetworkConnectionBuilder tls(TLSVersion version) {
            this.tlsVersions = EnumSet.of(version);
            return this;
        }

        /**
         * @param versions the TLS versions allowed for requests
         * @return itself
         */
        public OkHttpNetworkConnectionBuilder tls(EnumSet<TLSVersion> versions) {
            this.tlsVersions = versions;
            return this;
        }

        /**
         * @param emitTimeout The maximum timeout for emitting events. If emit time exceeds this value
         *                    TimeOutException will be thrown
         * @return itself
         */
        public OkHttpNetworkConnectionBuilder emitTimeout(int emitTimeout){
            this.emitTimeout = emitTimeout;
            return this;
        }

        /**
         * @param client An OkHttp client that will be used in the emitter, you can provide your
         *               own if you want to share your Singleton client's interceptors, connection pool etc..
         *               ,otherwise a new one is created.
         * @return itself
         */
        public OkHttpNetworkConnectionBuilder client(OkHttpClient client) {
            this.client = client;
            return this;
        }

        /**
         * @param customPostPath A custom path that is used on the endpoint to send requests.
         * @return itself
         */
        public OkHttpNetworkConnectionBuilder customPostPath(String customPostPath) {
            this.customPostPath = customPostPath;
            return this;
        }

        /**
         * Creates a new OkHttpNetworkConnection
         *
         * @return a new OkHttpNetworkConnection object
         */
        public OkHttpNetworkConnection build() {
            return new OkHttpNetworkConnection(this);
        }
    }

    private OkHttpNetworkConnection(OkHttpNetworkConnectionBuilder builder) {
        this.uri = builder.uri;
        this.protocol = builder.requestSecurity;
        this.httpMethod = builder.httpMethod;
        this.emitTimeout = builder.emitTimeout;
        this.customPostPath = builder.customPostPath;

        TLSArguments tlsArguments = new TLSArguments(builder.tlsVersions);
        buildUri();

        final OkHttpClient.Builder clientBuilder;
        if (builder.client == null) {
            clientBuilder = new OkHttpClient.Builder();
        } else {
            clientBuilder = builder.client.newBuilder();
        }

        this.client = clientBuilder.sslSocketFactory(tlsArguments.getSslSocketFactory(),
                tlsArguments.getTrustManager())
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    @Override
    @NonNull
    public HttpMethod getHttpMethod() {
        return httpMethod;
    }

    @NonNull
    @Override
    public Uri getUri() {
        return uriBuilder.clearQuery().build();
    }

    @NonNull
    @Override
    public List<RequestResult> sendRequests(@NonNull List<Request> requests) {
        List<Future> futures = new ArrayList<>();
        List<RequestResult> results = new ArrayList<>();

        // Start all requests in the ThreadPool
        for (Request request : requests) {
            String userAgent = request.customUserAgent != null ? request.customUserAgent : DEFAULT_USER_AGENT;

            okhttp3.Request okHttpRequest = httpMethod == HttpMethod.GET
                    ? buildGetRequest(request, userAgent)
                    : buildPostRequest(request, userAgent);

            futures.add(Executor.futureCallable(getRequestCallable(okHttpRequest)));
        }

        Logger.d(TAG, "Request Futures: %s", futures.size());

        // Get results of futures
        // - Wait up to emitTimeout seconds for the request
        for (int i = 0; i < futures.size(); i++) {
            int code = -1;

            try {
                code = (int) futures.get(i).get(emitTimeout, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Logger.e(TAG, "Request Future was interrupted: %s", ie.getMessage());
            } catch (ExecutionException ee) {
                Logger.e(TAG, "Request Future failed: %s", ee.getMessage());
            } catch (TimeoutException te) {
                Logger.e(TAG, "Request Future had a timeout: %s", te.getMessage());
            }

            Request request = requests.get(i);
            List<Long> eventIds = request.emitterEventIds;
            if (request.oversize) {
                Logger.track(TAG, "Request is oversized for emitter event IDs: %s", eventIds.toString());
                results.add(new RequestResult(true, eventIds));
            } else {
                results.add(new RequestResult(isSuccessfulSend(code), eventIds));
            }
        }
        return results;
    }

    private void buildUri() {
        String protocolString = protocol == HTTP ? "http://" : "https://";
        uriBuilder = Uri.parse(protocolString + uri).buildUpon();

        if (httpMethod == GET) {
            uriBuilder.appendPath("i");
        } else if (this.customPostPath == null) {
            uriBuilder.appendEncodedPath(TrackerConstants.PROTOCOL_VENDOR + "/" +
                    TrackerConstants.PROTOCOL_VERSION);
        } else {
            uriBuilder.appendEncodedPath(this.customPostPath);
        }
    }

    /**
     * Builds an OkHttp GET request which is ready
     * to be executed.
     * @param request The request where to get the payload to be sent.
     * @param userAgent The user-agent used during the transmission to the collector.
     * @return An OkHttp request object.
     */
    private okhttp3.Request buildGetRequest(Request request, String userAgent) {
        // Clear the previous query
        uriBuilder.clearQuery();

        // Build the request query
        HashMap hashMap = (HashMap) request.payload.getMap();

        for (String key : (Iterable<String>) hashMap.keySet()) {
            String value = (String) hashMap.get(key);
            uriBuilder.appendQueryParameter(key, value);
        }

        // Build the request
        String reqUrl = uriBuilder.build().toString();
        return new okhttp3.Request.Builder()
                .url(reqUrl)
                .header("User-Agent", userAgent)
                .get()
                .build();
    }

    /**
     * Builds an OkHttp POST request which is ready
     * to be executed.
     * @param request The request where to get the payload to be sent.
     * @param userAgent The user-agent used during the transmission to the collector.
     * @return An OkHttp request object.
     */
    private okhttp3.Request buildPostRequest(Request request, String userAgent) {
        String reqUrl = uriBuilder.build().toString();
        RequestBody reqBody = RequestBody.create(JSON, request.payload.toString());
        return new okhttp3.Request.Builder()
                .url(reqUrl)
                .header("User-Agent", userAgent)
                .post(reqBody)
                .build();
    }

    /**
     * Returns a Callable Request Send
     *
     * @param request the request to be
     *                sent
     * @return the new Callable object
     */
    private Callable<Integer> getRequestCallable(final okhttp3.Request request) {
        return () -> requestSender(request);
    }

    /**
     * The function responsible for actually sending
     * the request to the collector.
     *
     * @param request The request to be sent
     * @return a RequestResult
     */
    private int requestSender(okhttp3.Request request) {
        try {
            Logger.v(TAG, "Sending request: %s", request);

            Response resp = client.newCall(request).execute();
            int code = resp.code();
            resp.body().close();

            return code;
        } catch (IOException e) {
            Logger.e(TAG, "Request sending failed: %s", e.toString());
            return -1;
        }
    }

    /**
     * Returns truth on if the request
     * was sent successfully.
     *
     * @param code the response code
     * @return the truth as to the success
     */
    private boolean isSuccessfulSend(int code) {
        return code >= 200 && code < 300;
    }
}
