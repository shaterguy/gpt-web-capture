package com.shaterguy.gptwebcapture;

import android.net.http.SslError;
import android.webkit.ConsoleMessage;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class TrafficRecorder {
    private static final int MAX_NETWORK_EVENTS = 20000;
    private static final int MAX_CONSOLE_EVENTS = 10000;

    private final Object lock = new Object();
    private final List<JSONObject> networkEvents = new ArrayList<>();
    private final List<JSONObject> consoleEvents = new ArrayList<>();
    private int droppedNetwork;
    private int droppedConsole;

    void recordRequest(WebResourceRequest request, String source) {
        if (request == null) return;
        JSONObject event = base("request");
        try {
            event.put("source", source);
            event.put("url", SafeRedactor.redactUrl(String.valueOf(request.getUrl())));
            event.put("method", request.getMethod());
            event.put("mainFrame", request.isForMainFrame());
            event.put("hasGesture", request.hasGesture());
            event.put("headers", SafeRedactor.redactHeaders(request.getRequestHeaders()));
        } catch (Exception ignored) {}
        addNetwork(event);
    }

    void recordPage(String phase, String url) {
        JSONObject event = base("page");
        try {
            event.put("phase", phase);
            event.put("url", SafeRedactor.redactUrl(url));
        } catch (Exception ignored) {}
        addNetwork(event);
    }

    void recordHttpError(WebResourceRequest request, WebResourceResponse response) {
        JSONObject event = base("httpError");
        try {
            if (request != null) {
                event.put("url", SafeRedactor.redactUrl(String.valueOf(request.getUrl())));
                event.put("method", request.getMethod());
                event.put("mainFrame", request.isForMainFrame());
            }
            if (response != null) {
                event.put("statusCode", response.getStatusCode());
                event.put("reasonPhrase", SafeRedactor.scrubText(response.getReasonPhrase()));
                Map<String, String> headers = response.getResponseHeaders();
                event.put("headers", SafeRedactor.redactHeaders(headers));
                event.put("mimeType", response.getMimeType());
                event.put("encoding", response.getEncoding());
            }
        } catch (Exception ignored) {}
        addNetwork(event);
    }

    void recordResourceError(WebResourceRequest request, int code, String description) {
        JSONObject event = base("resourceError");
        try {
            if (request != null) {
                event.put("url", SafeRedactor.redactUrl(String.valueOf(request.getUrl())));
                event.put("method", request.getMethod());
                event.put("mainFrame", request.isForMainFrame());
            }
            event.put("errorCode", code);
            event.put("description", SafeRedactor.scrubText(description));
        } catch (Exception ignored) {}
        addNetwork(event);
    }

    void recordSslError(SslError error) {
        JSONObject event = base("sslError");
        try {
            if (error != null) {
                event.put("primaryError", error.getPrimaryError());
                event.put("url", SafeRedactor.redactUrl(error.getUrl()));
            }
        } catch (Exception ignored) {}
        addNetwork(event);
    }

    void recordConsole(ConsoleMessage message) {
        if (message == null) return;
        JSONObject event = base("console");
        try {
            event.put("level", String.valueOf(message.messageLevel()));
            event.put("message", SafeRedactor.scrubText(message.message()));
            event.put("sourceId", SafeRedactor.redactUrl(message.sourceId()));
            event.put("lineNumber", message.lineNumber());
        } catch (Exception ignored) {}
        synchronized (lock) {
            if (consoleEvents.size() >= MAX_CONSOLE_EVENTS) droppedConsole++;
            else consoleEvents.add(event);
        }
    }

    JSONObject networkSnapshot() {
        synchronized (lock) {
            JSONObject out = new JSONObject();
            JSONArray events = new JSONArray();
            for (JSONObject event : networkEvents) events.put(event);
            try {
                out.put("events", events);
                out.put("dropped", droppedNetwork);
                out.put("requestBodiesCaptured", false);
                out.put("responseBodiesCaptured", false);
            } catch (Exception ignored) {}
            return out;
        }
    }

    JSONObject consoleSnapshot() {
        synchronized (lock) {
            JSONObject out = new JSONObject();
            JSONArray events = new JSONArray();
            for (JSONObject event : consoleEvents) events.put(event);
            try {
                out.put("events", events);
                out.put("dropped", droppedConsole);
            } catch (Exception ignored) {}
            return out;
        }
    }

    private JSONObject base(String type) {
        JSONObject event = new JSONObject();
        try {
            event.put("type", type);
            event.put("timestampMs", System.currentTimeMillis());
            event.put("thread", Thread.currentThread().getName());
        } catch (Exception ignored) {}
        return event;
    }

    private void addNetwork(JSONObject event) {
        synchronized (lock) {
            if (networkEvents.size() >= MAX_NETWORK_EVENTS) droppedNetwork++;
            else networkEvents.add(event);
        }
    }
}
