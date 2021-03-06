package com.centurylink.mdw.cli;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class Fetch implements Operation {

    private URL from;
    private String data;
    public String getData() { return data; }

    private String contentType;
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Fetch(URL from) {
        this.from = from;
    }

    /**
     * Performs a GET request
     */
    public Fetch run(ProgressMonitor... progressMonitors) throws IOException {
        this.data = get();
        return this;
    }

    public String get() throws IOException {
        try (InputStream urlIn = new BufferedInputStream(from.openStream());
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int len = urlIn.read(buffer);
            while (len >= 0) {
                out.write(buffer, 0, len);
                len = urlIn.read(buffer);
            }
            return out.toString();
        }
    }

    public String put(String request) throws IOException {
        data = perform("PUT", request);
        return data;
    }

    public String post(String request) throws IOException {
        data = perform("POST", request);
        return data;
    }

    /**
     * For PUT/POST.
     */
    protected String perform(String method, String request) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) from.openConnection();
        connection.setRequestMethod(method);
        connection.setDoOutput(true);
        if (contentType == null)
            contentType = "text/plain; charset=utf8";
        connection.setRequestProperty("Content-Type", contentType);

        try (OutputStream urlOut = connection.getOutputStream()) {
            urlOut.write(request.getBytes());
            urlOut.flush();
            InputStream urlIn = connection.getInputStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len = urlIn.read(buffer);
            while (len >= 0) {
                resp.write(buffer, 0, len);
                len = urlIn.read(buffer);
            }
            return resp.toString();
        }
    }
}
