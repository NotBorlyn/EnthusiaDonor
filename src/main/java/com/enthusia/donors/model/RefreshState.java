package com.enthusia.donors.model;

public enum RefreshState {
    STARTING,
    OK,
    REFRESHING,
    TEBEX_NOT_CONFIGURED,
    TEBEX_FAILED,
    CACHE_ONLY,
    TEST_DATA
}
