package com.shaterguy.gptwebcapture;

import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;

final class CaptureWebChromeClient extends WebChromeClient {
    private final TrafficRecorder recorder;

    CaptureWebChromeClient(TrafficRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
        recorder.recordConsole(consoleMessage);
        return super.onConsoleMessage(consoleMessage);
    }
}
