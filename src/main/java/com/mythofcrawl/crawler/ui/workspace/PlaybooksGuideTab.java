package com.mythofcrawl.crawler.ui.workspace;

import com.mythofcrawl.crawler.core.guardrail.PolicyLedger;
import com.mythofcrawl.crawler.core.guardrail.PolicyRule;
import com.mythofcrawl.crawler.model.CrawlConfig;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.ui.kit.Pill;
import com.mythofcrawl.crawler.ui.kit.StructuredAdviceCard;
import com.mythofcrawl.crawler.ui.kit.ToastPane;
import com.mythofcrawl.crawler.ui.kit.WrappedText;
import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.Ui;
import com.mythofcrawl.crawler.ui.theme.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.Arrays;
import java.util.List;

/**
 * Playbooks & Workflow Guide Tab (LAW 1-4 compliant, zero HTML tags):
 * - Explains the 2-Stage Action Guardrail architecture and Crawl-First, Explicit Batch Push Later rule.
 * - Provides 5 One-Click Playbooks configuring PolicyLedger & CrawlConfig safely.
 */
public final class PlaybooksGuideTab extends JPanel {

    private final CrawlState state;
    private final CrawlConfig config;
    private final PolicyLedger ledger;
    private final ToastPane toast;
    private final Runnable onPlaybookApplied;

    private final Pill pillActiveRules = new Pill("0 luật đang bật", Theme.Token.INFO);
    private final Pill pillLastPlaybook = new Pill("Chưa chọn Playbook", Theme.Token.NEUTRAL);
    private final JPanel cardsHost = new ThemeSyncHelper.ViewportWidthFormPanel(null);
    private AutoCloseable themeSub;

    public PlaybooksGuideTab(
        CrawlState state,
        CrawlConfig config,
        PolicyLedger ledger,
        ToastPane toast,
        Runnable onPlaybookApplied
    ) {
        this.state = state;
        this.config = config;
        this.ledger = ledger != null ? ledger : new PolicyLedger();
        this.toast = toast;
        this.onPlaybookApplied = onPlaybookApplied;

        setLayout(new BorderLayout(Theme.SPACE_2, Theme.SPACE_2));
        setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));

        JPanel topStrip = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        topStrip.setOpaque(false);
        JLabel title = new JLabel("Hướng dẫn Quy trình & 5 Playbooks 1-Click");
        title.setFont(FontManager.uiHeading());

        JPanel statusPills = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_2, 0));
        statusPills.setOpaque(false);
        statusPills.add(pillLastPlaybook);
        statusPills.add(pillActiveRules);
        topStrip.add(title, BorderLayout.WEST);
        topStrip.add(statusPills, BorderLayout.EAST);

        cardsHost.setOpaque(false);
        cardsHost.setLayout(new BoxLayout(cardsHost, BoxLayout.Y_AXIS));

        StructuredAdviceCard archCard = new StructuredAdviceCard(
            Theme.Token.INFO,
            "Kiến trúc 2 Chặng + Crawl-First, Explicit Batch Push Later",
            "Bắt buộc",
            "Mọi tương tác đều đi theo luồng an toàn: Crawl & gom Action Path trước -> Duyệt / rà soát trên Cây -> Chỉ đẩy hàng loạt sang Logger++ khi bạn bấm nút thủ công.",
            Arrays.asList(
                "Chặng 1 (Action Gate ở Proxy): Đánh giá PolicyLedger (#1 ALLOW thắng tuyệt đối -> Scope -> Priority) để cho qua, giả lập HOLD 200 OK hoặc giữ tại Intercept Queue.",
                "Chặng 2 (Dữ liệu động): Thu hoạch Live ID từ response hoặc tạo dữ liệu nháp (Producer-Recreate) trước khi sửa/xóa.",
                "Crawl-First Push Later: ExtenderPushManager KHÔNG bao giờ tự đẩy trong khi crawl đang chạy; luôn xuất/đẩy chủ động ở tab Đẩy Logger++."
            )
        );
        archCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        cardsHost.add(archCard);
        cardsHost.add(Box.createVerticalStrut(Theme.SPACE_3));

        cardsHost.add(buildPlaybookCard(
            Theme.Token.SUCCESS,
            "Playbook A: Crawl chỉ đọc GET 100% (HOLD_AT_PROXY)",
            "An toàn tuyệt đối",
            "Giữ toàn bộ POST / PUT / PATCH / DELETE tại Proxy và trả HTTP 200 OK ảo (X-Myth-Proxy-Hold: 1) để SPA tiếp tục điều hướng mà không sửa/xóa dữ liệu thật trên server.",
            Arrays.asList(
                "Bật luật Hold 200 cho POST, PUT, PATCH, DELETE.",
                "Khóa Auto-Request nguy hiểm nếu đang dùng mặc định."
            ),
            "Áp dụng Playbook A",
            () -> {
                ledger.installSafeReadOnlyPlaybook();
                applyDone("Playbook A (Chỉ đọc GET)");
            }
        ));
        cardsHost.add(Box.createVerticalStrut(Theme.SPACE_2));

        cardsHost.add(buildPlaybookCard(
            Theme.Token.WARNING,
            "Playbook B: Crawl full + Chặn duyệt POST/PUT/PATCH/DELETE (INTERCEPT_WAIT)",
            "Giữ duyệt tay",
            "Tất cả GET đi qua bình thường, còn các thao tác DELETE / PUT / PATCH hoặc nút delete/submit sẽ dừng ở hàng chờ Proxy Intercept Queue để bạn xem Raw HTTP rồi chọn Gửi, Lưu nháp hoặc Hủy.",
            Arrays.asList(
                "Cài luật INTERCEPT_WAIT cho DELETE / PUT / PATCH & uiAction delete/submit.",
                "Timeout an toàn mặc định tự DROP nếu không duyệt."
            ),
            "Áp dụng Playbook B",
            () -> {
                ledger.installProductionGuardPlaybook();
                applyDone("Playbook B (Chặn duyệt Mutations)");
            }
        ));
        cardsHost.add(Box.createVerticalStrut(Theme.SPACE_2));

        cardsHost.add(buildPlaybookCard(
            Theme.Token.ACCENT,
            "Playbook C: Crawl theo mục tiêu Excel {placeholder} + Live ID Pool",
            "Coverage mục tiêu",
            "Bật Deep Crawl và kích hoạt Stage-2 Live ID Substitution để gom ID sống từ GET/POST và đối chiếu chuẩn với các đường dẫn {session} / {tenant} / {id} trong file Excel.",
            Arrays.asList(
                "Bật Deep Crawl Mode trong CrawlConfig.",
                "Cài luật LIVE_ID_POOL tự thay thế ID sống khi kiểm thử."
            ),
            "Áp dụng Playbook C",
            () -> {
                if (config != null) {
                    config.setDeepCrawlMode(true);
                }
                ledger.installLiveIdTestPlaybook();
                applyDone("Playbook C (Excel + Live ID)");
            }
        ));
        cardsHost.add(Box.createVerticalStrut(Theme.SPACE_2));

        cardsHost.add(buildPlaybookCard(
            Theme.Token.DUP,
            "Playbook D: Kiểm thử phân quyền đa Role + Lock non-2xx",
            "Multi-Role Auth",
            "Khóa retry mutation khi gặp lỗi non-2xx và chuẩn bị đối chiếu quyền giữa ADMIN / USER / GUEST ở tab Roles & Auth (hỗ trợ giải mã JWT & đếm ngược TTL).",
            Arrays.asList(
                "Cài luật LOCK_2XX cho POST, PUT, DELETE.",
                "Chuyển qua tab Roles & Auth để khai báo Cookie / Bearer JWT từng Role."
            ),
            "Áp dụng Playbook D",
            () -> {
                ledger.installStrict2xxLockPlaybook();
                applyDone("Playbook D (Multi-Role Auth)");
            }
        ));
        cardsHost.add(Box.createVerticalStrut(Theme.SPACE_2));

        cardsHost.add(buildPlaybookCard(
            Theme.Token.NEUTRAL,
            "Playbook E: Nhả hết về Nguyên bản (NONE)",
            "Không chặn",
            "Đưa toàn bộ các luật chặn/giữ trong PolicyLedger về trạng thái NONE (tắt chặn) để Proxy hoạt động nguyên bản 100%.",
            Arrays.asList(
                "Gọi releaseAllToNone() trên toàn bộ PolicyLedger.",
                "Phù hợp khi muốn đo đạc traffic gốc trên môi trường lab/staging."
            ),
            "Nhả hết về NONE",
            () -> {
                ledger.releaseAllToNone();
                applyDone("Playbook E (Nguyên bản NONE)");
            }
        ));
        cardsHost.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(cardsHost);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        add(topStrip, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        this.themeSub = Theme.onThemeChange(this::applyTheme);
        applyTheme();
        refreshData();
    }

    private JPanel buildPlaybookCard(
        Theme.Token token,
        String title,
        String badgeText,
        String summary,
        List<String> steps,
        String actionText,
        Runnable onApply
    ) {
        StructuredAdviceCard advice = new StructuredAdviceCard(token, title, badgeText, summary, steps);
        Ui.UiButton btn = (token == Theme.Token.NEUTRAL)
            ? Ui.secondaryBtn(actionText, VectorIcons.unlock(Theme.text()))
            : Ui.solidPrimary(actionText, VectorIcons.play(Theme.inkWhite()));
        btn.addActionListener(e -> {
            if (onApply != null) {
                onApply.run();
            }
        });

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        btnRow.setOpaque(false);
        btnRow.add(btn);

        JPanel wrap = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        wrap.setOpaque(false);
        wrap.add(advice, BorderLayout.CENTER);
        wrap.add(btnRow, BorderLayout.SOUTH);

        JPanel card = Ui.cardPanel(wrap);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        return card;
    }

    private void applyDone(String label) {
        pillLastPlaybook.setPill("Đã áp dụng: " + label, Theme.Token.SUCCESS, false);
        refreshData();
        if (toast != null) {
            toast.show(Theme.Token.SUCCESS, "Đã áp dụng Playbook", label);
        }
        if (onPlaybookApplied != null) {
            onPlaybookApplied.run();
        }
    }

    public void refreshData() {
        int active = 0;
        for (PolicyRule r : ledger.snapshotRules()) {
            if (r.enabled) {
                active++;
            }
        }
        pillActiveRules.setPill(active + " luật đang bật", active > 0 ? Theme.Token.ACCENT : Theme.Token.NEUTRAL, false);
    }

    private void applyTheme() {
        setBackground(Theme.canvas());
        ThemeSyncHelper.applyTree(this);
        for (Component c : getComponents()) {
            if (c instanceof JScrollPane) {
                JScrollPane sp = (JScrollPane) c;
                sp.setBorder(BorderFactory.createEmptyBorder());
                if (sp.getViewport() != null) {
                    sp.getViewport().setBackground(Theme.canvas());
                }
            }
        }
        for (Component c : cardsHost.getComponents()) {
            if (c instanceof JPanel && c.isOpaque()) {
                c.setBackground(Theme.surface());
                ((JPanel) c).setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.border(), 1, true),
                    BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3)));
            }
        }
        repaint();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (cardsHost != null && pillActiveRules != null) {
            applyTheme();
        }
    }
}
