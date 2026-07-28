package com.atuy.hoyogachahistory;

interface IPrivilegedService {
    void destroy() = 16777114;
    boolean clearLogcat() = 1;
    String captureUrl(String gameMarker, int timeoutMs) = 2;
    String identity() = 3;
}
