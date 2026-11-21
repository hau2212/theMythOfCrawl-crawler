package com.mythofcrawl.crawler.ui;

import burp.api.montoya.MontoyaApi;
import com.mythofcrawl.crawler.model.ActionGroupStore;
import com.mythofcrawl.crawler.model.ApiCall;
import com.mythofcrawl.crawler.model.CapturedApiEntry;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.List;
import java.util.function.Consumer;

/**
 * Legacy placeholder retained only for source compatibility; its vertical 14-inch stack
 * and overlap comparison views are now unified inside {@link GroupsWorkspacePanel}.
 */
@Deprecated
public final class Compact14ViewPanel extends JPanel {

    public enum ViewMode {
        TREE_STACK,
        DUP_PATH,
        DIFF_NOTE_ONLY,
        FLAT_FILTER
    }

    public Compact14ViewPanel(
        MontoyaApi api,
        ActionGroupStore store,
        Consumer<ApiCall> onInspectCall,
        Consumer<List<ApiCall>> onQueuePush,
        Consumer<CapturedApiEntry> onSendRepeater
    ) {
        super(new BorderLayout());
        add(new JLabel("Đã hợp nhất vào tab Workspace (Nhóm & API) -> chế độ 'Sổ dọc 14\"'."), BorderLayout.CENTER);
    }

    public void applyViewMode(ViewMode mode) {
    }

    public void refreshData() {
    }
}
