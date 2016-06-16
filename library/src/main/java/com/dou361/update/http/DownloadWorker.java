package com.dou361.update.http;

import android.text.TextUtils;

import com.dou361.update.listener.DownloadListener;
import com.dou361.update.util.HandlerUtil;
import com.dou361.update.util.UpdateSP;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * @author Administrator
 */
public class DownloadWorker implements Runnable {

    HttpURLConnection urlConn;
    protected String url;
    protected DownloadListener downloadCB;
    protected File cacheFileName;

    public void setUrl(String url) {
        this.url = url;
    }

    public void setCacheFileName(File cacheFileName) {
        this.cacheFileName = cacheFileName;
    }

    public void setDownloadListener(DownloadListener downloadCB) {
        this.downloadCB = downloadCB;
    }

    protected void download(String url, File target) throws Exception {
        URL httpUrl = new URL(url);
        urlConn = (HttpURLConnection) httpUrl.openConnection();
        setDefaultProperties();
        urlConn.connect();

        int responseCode = urlConn.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            throw new HttpException(responseCode, urlConn.getResponseMessage());
        }

        long contentLength = urlConn.getContentLength();
        if (checkIsDownAll(target, url, contentLength)) {
            urlConn.disconnect();
            urlConn = null;
            return;
        }
        RandomAccessFile raf = supportBreakpointDownload(target, httpUrl, url);

        long offset = target.exists() ? (int) target.length() : 0;
        InputStream inputStream = urlConn.getInputStream();
        byte[] buffer = new byte[8 * 1024];
        int length;
        long start = System.currentTimeMillis();

        while ((length = inputStream.read(buffer)) != -1) {
            raf.write(buffer, 0, length);
            offset += length;
            UpdateSP.saveDownloadSize(url, offset);
            long end = System.currentTimeMillis();
            if (end - start > 1000) {
                sendUpdateProgress(offset, contentLength);
            }
        }

        urlConn.disconnect();
        raf.close();
        urlConn = null;
    }

    @Override
    public void run() {
        cacheFileName.getParentFile().mkdirs();
        try {
            sendUpdateStart();
            download(url, cacheFileName);
            sendUpdateComplete(cacheFileName);
        } catch (HttpException he) {
            sendUpdateError(he.getCode(), he.getErrorMsg());
        } catch (Exception e) {
            sendUpdateError(-1, e.getMessage());
        }
    }

    private void sendUpdateStart() {
        if (downloadCB == null) return;

        HandlerUtil.getMainHandler().post(new Runnable() {
            @Override
            public void run() {
                downloadCB.onUpdateStart();
            }
        });
    }

    protected void sendUpdateProgress(final long current, final long total) {
        if (downloadCB == null) return;

        HandlerUtil.getMainHandler().post(new Runnable() {
            @Override
            public void run() {
                downloadCB.onUpdateProgress(current, total);
            }
        });
    }

    private void sendUpdateComplete(final File file) {
        if (downloadCB == null) return;

        HandlerUtil.getMainHandler().post(new Runnable() {
            @Override
            public void run() {
                downloadCB.onUpdateComplete(file);
            }
        });
    }

    private void sendUpdateError(final int code, final String errorMsg) {
        if (downloadCB == null) return;

        HandlerUtil.getMainHandler().post(new Runnable() {
            @Override
            public void run() {
                downloadCB.onUpdateError(code, errorMsg);
            }
        });
    }

    private boolean checkIsDownAll(File target, String url, long contentLength) {
        long lastDownSize = UpdateSP.getLastDownloadSize(url);
        long length = target.length();
        long lastTotalSize = UpdateSP.getLastDownloadTotalSize(url);
        if (lastDownSize == length
                && lastTotalSize == lastTotalSize
                && lastDownSize != 0
                && lastDownSize == contentLength)
            return true;
        return false;
    }

    private RandomAccessFile supportBreakpointDownload(File target, URL httpUrl, String url) throws IOException {

        String range = urlConn.getHeaderField("Accept-Ranges");
        if (TextUtils.isEmpty(range) || !range.startsWith("bytes")) {
            target.delete();
            return new RandomAccessFile(target, "rw");
        }

        long lastDownSize = UpdateSP.getLastDownloadSize(url);
        long length = target.length();
        long lastTotalSize = UpdateSP.getLastDownloadTotalSize(url);
        long contentLength = Long.parseLong(urlConn.getHeaderField("Content-Length"));
        UpdateSP.saveDownloadTotalSize(url, contentLength);
        if (lastTotalSize != contentLength
                || lastDownSize != length
                || lastDownSize > contentLength) {
            target.delete();
            return new RandomAccessFile(target, "rw");
        }
        urlConn.disconnect();
        urlConn = (HttpURLConnection) httpUrl.openConnection();

        urlConn.setRequestProperty("RANGE", "bytes=" + length + "-" + contentLength);
        setDefaultProperties();
        urlConn.connect();

        int responseCode = urlConn.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            throw new HttpException(responseCode, urlConn.getResponseMessage());
        }
        RandomAccessFile raf = new RandomAccessFile(target, "rw");
        raf.seek(length);

        return raf;
    }

    private void setDefaultProperties() throws IOException {
        urlConn.setRequestProperty("Content-Type", "text/html; charset=UTF-8");
        urlConn.setRequestMethod("GET");
        urlConn.setConnectTimeout(10000);
        //   urlConn.setDoOutput(true);  这会把request method 强制变成post请求 造成下载失败
        //   urlConn.setDoInput(true);
    }
}