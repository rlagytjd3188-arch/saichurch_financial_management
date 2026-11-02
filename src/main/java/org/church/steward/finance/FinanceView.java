package org.church.steward.finance;

import java.awt.*;
import javax.swing.*;
import org.church.steward.infrastructure.annotation.UiView;
import org.church.steward.ui.MenuActivatable;

/** 기존 Swing 메뉴를 유지하고 반응형 회계·헌금·보고 화면을 연결한다. */
@UiView
public class FinanceView extends JPanel implements MenuActivatable {
    private static final long serialVersionUID = 1L;

    public FinanceView(FinanceWeb web) {
        setLayout(new GridLayout(0, 1, 12, 12));
        setBorder(BorderFactory.createEmptyBorder(40, 40, 40, 40));
        add(new JLabel("사이교회 재무관리 · 회계와 헌금", SwingConstants.CENTER));
        for (String page : new String[] { "대시보드", "거래", "헌금 관리", "헌금자", "계정과목", "재무상태표", "통계", "재무상태 보고서", "외부 조회" }) {
            JButton button = new JButton(page + " 열기");
            button.addActionListener(e -> {
                try {
                    Desktop.getDesktop().browse(web.open(page));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, ex.getMessage());
                }
            });
            add(button);
        }
        add(new JLabel("기존 수입·지출 입력은 그대로 사용할 수 있습니다. 과거 거래는 회계 연결 검토 후 반영합니다."));
    }

    @Override
    public void onMenuSelected() {
    }
}
