package com.mythofcrawl.crawler.core.tree;

/**
 * Semantic UI/Network node types rendered in the Tree-First Explorer with stable vector icon keys.
 */
public enum NodeTypeCatalog {
    SCREEN("Màn hình / Route", "screen"),
    MENU("Menu / Điều hướng", "menu"),
    BUTTON("Nút bấm / Action", "button"),
    MODAL("Hộp thoại / Drawer", "modal"),
    FORM("Biểu mẫu (Form)", "form"),
    API("HTTP API Call", "api"),
    WEBSOCKET("WebSocket Frame", "websocket"),
    GHOST("API tĩnh chưa chạy (Ghost)", "ghost");

    public final String label;
    public final String iconKey;

    NodeTypeCatalog(String label, String iconKey) {
        this.label = label;
        this.iconKey = iconKey;
    }
}
