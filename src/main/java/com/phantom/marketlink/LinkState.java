package com.phantom.marketlink;

// UI state for the Litematica main-menu button.
public enum LinkState {
    LOGGED_OUT,
    LOGGING_IN,   // detail = user code
    CONNECTING,
    CONNECTED,    // detail = username
    RECONNECTING
}
