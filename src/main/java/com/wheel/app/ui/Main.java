package com.wheel.app.ui;

import com.wheel.app.audio.AudioService;
import com.wheel.app.format.WheelFormats;
import com.wheel.app.model.Models;
import com.wheel.app.model.Models.*;
import com.wheel.app.search.WheelSearch;
import com.wheel.app.spin.SpinEngine;
import com.wheel.app.storage.WheelRepository;
import com.wheel.app.tts.TtsService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.*;

public final class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Main().show());
    }

    private final WheelRepository repository = new WheelRepository(WheelRepository.defaultPath());
    private final SpinEngine spinEngine = new SpinEngine();
    private final AudioService audio = new AudioService();
    private final TtsService tts = new TtsService();
    private WheelLibrary library = repository.load();
    private Wheel selectedWheel;
    private String selectedGroupId;
    private JFrame frame;
    private DefaultListModel<WheelSearch.Result> wheelListModel = new DefaultListModel<>();
    private JList<WheelSearch.Result> wheelList;
    private JTree groupTree;
    private JTextField searchField;
    private WheelCanvas wheelCanvas;
    private JLabel status;

    private void show() {
        seedIfEmpty();
        frame = new JFrame("WWheel 转盘");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setSize(1180, 760);
        frame.setLocationRelativeTo(null);
        frame.setContentPane(buildUi());
        refreshGroups();
        refreshWheels();
        frame.setVisible(true);
    }

    private JComponent buildUi() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel left = new JPanel(new BorderLayout(8, 8));
        left.setPreferredSize(new Dimension(330, 600));
        searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "搜索转盘/选项，标题权重更高");
        searchField.addActionListener(e -> refreshWheels());
        searchField.getDocument().addDocumentListener((SimpleDocListener) e -> refreshWheels());
        left.add(searchField, BorderLayout.NORTH);

        JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        groupTree = new JTree();
        groupTree.addTreeSelectionListener(e -> {
            Object node = groupTree.getLastSelectedPathComponent();
            selectedGroupId = node instanceof GroupNode gn ? gn.groupId : null;
            refreshWheels();
        });
        leftSplit.setTopComponent(new JScrollPane(groupTree));

        wheelList = new JList<>(wheelListModel);
        wheelList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel label = new JLabel("<html><b>" + esc(value.wheel().name) + "</b><br><small>匹配分 " + value.score() + " · 选项 " + value.wheel().options.size() + "</small></html>");
            label.setOpaque(true);
            label.setBorder(new EmptyBorder(6, 6, 6, 6));
            label.setBackground(isSelected ? new Color(210, 228, 255) : Color.WHITE);
            return label;
        });
        wheelList.addListSelectionListener(e -> {
            WheelSearch.Result r = wheelList.getSelectedValue();
            if (r != null) selectWheel(r.wheel());
        });
        leftSplit.setBottomComponent(new JScrollPane(wheelList));
        leftSplit.setResizeWeight(0.35);
        left.add(leftSplit, BorderLayout.CENTER);

        JPanel leftButtons = new JPanel(new GridLayout(0, 2, 6, 6));
        leftButtons.add(button("新建分组", this::newGroup));
        leftButtons.add(button("新建转盘", this::newWheel));
        leftButtons.add(button("导入 PWH", this::importPwh));
        leftButtons.add(button("导入 WWD", this::importWwd));
        leftButtons.add(button("导出 PWH", this::exportPwh));
        leftButtons.add(button("导出 WWD", this::exportWwd));
        left.add(leftButtons, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        wheelCanvas = new WheelCanvas();
        center.add(wheelCanvas, BorderLayout.CENTER);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER));
        controls.add(button("开始旋转", this::spin));
        controls.add(button("编辑转盘", this::editWheel));
        controls.add(button("删除转盘", this::deleteWheel));
        status = new JLabel(" ");
        controls.add(status);
        center.add(controls, BorderLayout.SOUTH);

        root.add(left, BorderLayout.WEST);
        root.add(center, BorderLayout.CENTER);
        return root;
    }

    private JButton button(String text, java.util.function.Consumer<ActionEvent> action) {
        JButton b = new JButton(text);
        b.addActionListener(action::accept);
        return b;
    }

    private void seedIfEmpty() {
        if (!library.wheels.isEmpty()) return;
        Wheel w = new Wheel();
        w.name = "示例转盘";
        w.options.add(new WheelOption("选项 1", 1, null));
        w.options.add(new WheelOption("选项 2", 2, null));
        w.options.add(new WheelOption("选项 3", 1, null));
        library.wheels.add(w);
        selectedWheel = w;
    }

    private void refreshWheels() {
        wheelListModel.clear();
        List<Wheel> wheels = library.wheels.stream()
            .filter(w -> selectedGroupId == null || Objects.equals(w.groupId, selectedGroupId))
            .toList();
        for (WheelSearch.Result r : WheelSearch.search(wheels, searchField == null ? "" : searchField.getText())) wheelListModel.addElement(r);
        if (selectedWheel == null && wheelListModel.size() > 0) selectWheel(wheelListModel.get(0).wheel());
    }

    private void refreshGroups() {
        GroupNode root = new GroupNode("全部分组", null);
        addChildren(root, null);
        groupTree.setModel(new javax.swing.tree.DefaultTreeModel(root));
        for (int i = 0; i < groupTree.getRowCount(); i++) groupTree.expandRow(i);
    }

    private void addChildren(GroupNode parent, String parentId) {
        library.groups.stream().filter(g -> Objects.equals(g.parentId, parentId)).sorted(Comparator.comparing(g -> g.name)).forEach(g -> {
            GroupNode node = new GroupNode(g.name, g.id);
            parent.add(node);
            addChildren(node, g.id);
        });
    }

    private void selectWheel(Wheel wheel) {
        selectedWheel = wheel;
        wheelCanvas.setWheel(wheel);
        status.setText("当前：" + wheel.name);
    }

    private void newGroup(ActionEvent e) {
        JTextField name = new JTextField();
        JComboBox<GroupChoice> parent = groupSelector(true);
        if (JOptionPane.showConfirmDialog(frame, panel(new JLabel("分组名"), name, new JLabel("父分组"), parent), "新建分组", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            if (name.getText().isBlank()) return;
            WheelGroup g = new WheelGroup();
            g.name = name.getText().trim();
            g.parentId = ((GroupChoice) parent.getSelectedItem()).id;
            library.groups.add(g);
            save(); refreshGroups();
        }
    }

    private void newWheel(ActionEvent e) { editWheelDialog(null); }
    private void editWheel(ActionEvent e) { if (selectedWheel != null) editWheelDialog(selectedWheel); }

    private void editWheelDialog(Wheel existing) {
        Wheel w = existing == null ? new Wheel() : existing;
        JTextField name = new JTextField(w.name);
        JComboBox<GroupChoice> group = groupSelector(true);
        selectCombo(group, w.groupId == null ? selectedGroupId : w.groupId);
        JTextArea options = new JTextArea(optionsText(w), 10, 40);
        JSpinner duration = new JSpinner(new SpinnerNumberModel((int) (w.settings.rotationDurationMs / 1000), 1, 30, 1));
        JComboBox<String> colors = new JComboBox<>(new String[]{"classic", "pastel", "vivid", "mono"});
        colors.setSelectedItem(w.settings.colorScheme);
        JSpinner font = new JSpinner(new SpinnerNumberModel(w.settings.fontSize, 10, 36, 1));
        JCheckBox tick = new JCheckBox("经过选项音效", w.settings.tickSoundEnabled);
        JCheckBox selected = new JCheckBox("选中音效", w.settings.selectedSoundEnabled);
        JCheckBox ttsBox = new JCheckBox("系统 TTS 播放结果", w.settings.ttsEnabled);
        JPanel p = panel(new JLabel("转盘名字"), name, new JLabel("分组"), group, new JLabel("选项：每行 名字,真权重,假权重(可空)"), new JScrollPane(options), new JLabel("旋转时长(秒)"), duration, new JLabel("配色"), colors, new JLabel("字体大小"), font, tick, selected, ttsBox);
        if (JOptionPane.showConfirmDialog(frame, p, existing == null ? "新建转盘" : "编辑转盘", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        List<WheelOption> parsed = parseOptions(options.getText());
        if (name.getText().isBlank() || parsed.size() < 1) { JOptionPane.showMessageDialog(frame, "请填写名字并至少添加一个选项"); return; }
        w.name = name.getText().trim();
        w.groupId = ((GroupChoice) group.getSelectedItem()).id;
        w.options = new ArrayList<>(parsed);
        w.settings.rotationDurationMs = ((Number) duration.getValue()).longValue() * 1000;
        w.settings.colorScheme = Objects.toString(colors.getSelectedItem(), "classic");
        w.settings.fontSize = (Integer) font.getValue();
        w.settings.tickSoundEnabled = tick.isSelected();
        w.settings.selectedSoundEnabled = selected.isSelected();
        w.settings.ttsEnabled = ttsBox.isSelected();
        w.updatedAt = System.currentTimeMillis();
        if (existing == null) library.wheels.add(w);
        save(); refreshWheels(); selectWheel(w);
    }

    private List<WheelOption> parseOptions(String text) {
        List<WheelOption> opts = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            String[] parts = line.split(",", -1);
            String label = parts[0].trim();
            double trueWeight = parts.length > 1 && !parts[1].isBlank() ? Double.parseDouble(parts[1].trim()) : 1.0;
            Double fake = parts.length > 2 && !parts[2].isBlank() ? Double.parseDouble(parts[2].trim()) : null;
            if (!label.isBlank() && trueWeight > 0 && (fake == null || fake > 0)) opts.add(new WheelOption(label, trueWeight, fake));
        }
        return opts;
    }

    private String optionsText(Wheel w) {
        StringBuilder b = new StringBuilder();
        for (WheelOption o : w.options) b.append(o.text).append(',').append(o.trueWeight).append(',').append(o.fakeWeight == null ? "" : o.fakeWeight).append('\n');
        return b.toString();
    }

    private void spin(ActionEvent e) {
        if (selectedWheel == null || selectedWheel.options.isEmpty() || wheelCanvas.spinning) return;
        SpinEngine.SpinPlan plan = spinEngine.createPlan(selectedWheel);
        wheelCanvas.spin(plan, option -> { if (selectedWheel.settings.tickSoundEnabled) audio.tick(); }, () -> {
            status.setText("选中：" + plan.target().text);
            SpinHistoryEntry entry = new SpinHistoryEntry(); entry.wheelId = selectedWheel.id; entry.wheelName = selectedWheel.name; entry.optionId = plan.target().id; entry.optionText = plan.target().text; library.history.add(entry); save();
            if (selectedWheel.settings.selectedSoundEnabled) audio.selected();
            if (selectedWheel.settings.ttsEnabled) tts.speak(plan.target().text);
        });
    }

    private void deleteWheel(ActionEvent e) {
        if (selectedWheel == null) return;
        if (JOptionPane.showConfirmDialog(frame, "删除转盘 " + selectedWheel.name + "？") == JOptionPane.OK_OPTION) {
            library.wheels.remove(selectedWheel); selectedWheel = null; save(); refreshWheels(); wheelCanvas.setWheel(null);
        }
    }

    private void importPwh(ActionEvent e) { importFile("pwh", file -> WheelFormats.importPwh(Files.readAllBytes(file.toPath()), selectedGroupId).forEach(w -> { w.name = library.uniqueWheelName(w.name); library.wheels.add(w); })); }
    private void importWwd(ActionEvent e) { importFile("wwd/json", file -> { WheelLibrary imported = WheelFormats.importWwd(Files.readAllBytes(file.toPath())); mergeWwd(imported); }); }
    private void exportPwh(ActionEvent e) { exportFile("pwh", "export.pwh", file -> Files.write(file.toPath(), WheelFormats.exportPwh(library.wheels))); }
    private void exportWwd(ActionEvent e) { exportFile("wwd", "export.wwd", file -> Files.write(file.toPath(), WheelFormats.exportWwd(library, false))); }

    private void mergeWwd(WheelLibrary imported) {
        Set<String> usedGroupIds = new HashSet<>();
        for (WheelGroup group : library.groups) usedGroupIds.add(group.id);
        Map<String, String> groupIds = new HashMap<>();
        for (WheelGroup group : imported.groups) {
            String oldId = group.id;
            if (usedGroupIds.contains(group.id)) group.id = Models.newId();
            usedGroupIds.add(group.id); groupIds.putIfAbsent(oldId, group.id);
        }
        for (WheelGroup group : imported.groups) if (groupIds.containsKey(group.parentId)) group.parentId = groupIds.get(group.parentId);
        for (Wheel wheel : imported.wheels) if (groupIds.containsKey(wheel.groupId)) wheel.groupId = groupIds.get(wheel.groupId);
        Set<String> usedWheelIds = new HashSet<>();
        for (Wheel wheel : library.wheels) usedWheelIds.add(wheel.id);
        Map<String, String> wheelIds = new HashMap<>();
        for (Wheel wheel : imported.wheels) {
            String oldId = wheel.id;
            if (usedWheelIds.contains(wheel.id)) wheel.id = Models.newId();
            usedWheelIds.add(wheel.id); wheelIds.putIfAbsent(oldId, wheel.id);
        }
        for (SpinHistoryEntry entry : imported.history) if (wheelIds.containsKey(entry.wheelId)) entry.wheelId = wheelIds.get(entry.wheelId);
        library.groups.addAll(imported.groups); library.wheels.addAll(imported.wheels); library.history.addAll(imported.history);
    }

    private interface FileAction { void run(File file) throws Exception; }
    private void importFile(String ext, FileAction action) {
        JFileChooser c = new JFileChooser(); c.setFileFilter(new FileNameExtensionFilter(ext, ext.split("/")));
        if (c.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) try { action.run(c.getSelectedFile()); save(); refreshGroups(); refreshWheels(); } catch (Exception ex) { JOptionPane.showMessageDialog(frame, ex.getMessage()); }
    }
    private void exportFile(String ext, String name, FileAction action) {
        JFileChooser c = new JFileChooser(); c.setSelectedFile(new File(name));
        if (c.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) try { action.run(c.getSelectedFile()); } catch (Exception ex) { JOptionPane.showMessageDialog(frame, ex.getMessage()); }
    }

    private JComboBox<GroupChoice> groupSelector(boolean includeRoot) {
        JComboBox<GroupChoice> box = new JComboBox<>();
        if (includeRoot) box.addItem(new GroupChoice("未分组/全部", null));
        addGroupChoices(box, null, "");
        return box;
    }
    private void addGroupChoices(JComboBox<GroupChoice> box, String parentId, String prefix) { library.groups.stream().filter(g -> Objects.equals(g.parentId, parentId)).forEach(g -> { box.addItem(new GroupChoice(prefix + g.name, g.id)); addGroupChoices(box, g.id, prefix + "  / "); }); }
    private void selectCombo(JComboBox<GroupChoice> box, String id) { for (int i = 0; i < box.getItemCount(); i++) if (Objects.equals(box.getItemAt(i).id, id)) box.setSelectedIndex(i); }
    private void save() { try { repository.save(library); } catch (Exception ex) { JOptionPane.showMessageDialog(frame, "保存失败：" + ex.getMessage()); } }
    private JPanel panel(Component... comps) { JPanel p = new JPanel(new GridLayout(0, 1, 4, 4)); p.setBorder(new EmptyBorder(8, 8, 8, 8)); for (Component c : comps) p.add(c); return p; }
    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private record GroupChoice(String label, String id) { public String toString() { return label; } }
    private static final class GroupNode extends javax.swing.tree.DefaultMutableTreeNode { final String groupId; GroupNode(String name, String groupId) { super(name); this.groupId = groupId; } }

    @FunctionalInterface private interface SimpleDocListener extends javax.swing.event.DocumentListener {
        void update(javax.swing.event.DocumentEvent e);
        default void insertUpdate(javax.swing.event.DocumentEvent e) { update(e); }
        default void removeUpdate(javax.swing.event.DocumentEvent e) { update(e); }
        default void changedUpdate(javax.swing.event.DocumentEvent e) { update(e); }
    }

    private final class WheelCanvas extends JPanel {
        private Wheel wheel;
        private double rotation;
        private boolean spinning;
        private String lastPassed;
        private long lastTick;
        WheelCanvas() { setBackground(Color.WHITE); }
        void setWheel(Wheel wheel) { this.wheel = wheel; repaint(); }
        void spin(SpinEngine.SpinPlan plan, java.util.function.Consumer<WheelOption> pass, Runnable done) {
            spinning = true; lastPassed = null; long start = System.currentTimeMillis(); double from = rotation; double to = rotation + plan.totalRotation();
            javax.swing.Timer timer = new javax.swing.Timer(16, null);
            timer.addActionListener(ev -> {
                double t = Math.min(1, (System.currentTimeMillis() - start) / (double) plan.durationMs());
                double eased = 1 - Math.pow(1 - t, 3);
                rotation = from + (to - from) * eased;
                WheelOption current = spinEngine.optionAtPointer(wheel, rotation);
                long now = System.currentTimeMillis();
                if (current != null && !Objects.equals(current.id, lastPassed) && now - lastTick > 45) { lastPassed = current.id; lastTick = now; pass.accept(current); }
                repaint();
                if (t >= 1) { timer.stop(); spinning = false; done.run(); }
            });
            timer.start();
        }
        protected void paintComponent(Graphics g) {
            super.paintComponent(g); Graphics2D g2 = (Graphics2D) g.create(); g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int size = Math.min(getWidth(), getHeight()) - 60; int x = (getWidth() - size) / 2; int y = (getHeight() - size) / 2;
            if (wheel == null) { g2.drawString("请新建或选择一个转盘", 30, 30); g2.dispose(); return; }
            java.util.List<SpinEngine.Segment> segs = spinEngine.segments(wheel); Color[] colors = colors(wheel.settings.colorScheme);
            for (int i = 0; i < segs.size(); i++) {
                SpinEngine.Segment s = segs.get(i); g2.setColor(colors[i % colors.length]);
                g2.fill(new Arc2D.Double(x, y, size, size, -(s.startAngle() + rotation), -s.sweepAngle(), Arc2D.PIE));
                double mid = Math.toRadians(s.startAngle() + s.sweepAngle() / 2 + rotation); int tx = (int) (x + size / 2.0 + Math.cos(mid) * size * 0.28); int ty = (int) (y + size / 2.0 - Math.sin(mid) * size * 0.28);
                g2.setColor(Color.BLACK); g2.setFont(getFont().deriveFont((float) wheel.settings.fontSize)); drawCentered(g2, s.option().text, tx, ty);
            }
            g2.setColor(Color.DARK_GRAY); g2.setStroke(new BasicStroke(3)); g2.drawOval(x, y, size, size);
            Path2D pointer = new Path2D.Double(); pointer.moveTo(getWidth()/2.0, y - 6); pointer.lineTo(getWidth()/2.0 - 14, y - 34); pointer.lineTo(getWidth()/2.0 + 14, y - 34); pointer.closePath(); g2.setColor(Color.RED); g2.fill(pointer);
            g2.dispose();
        }
        private void drawCentered(Graphics2D g, String text, int x, int y) { FontMetrics fm = g.getFontMetrics(); String t = text.length() > 14 ? text.substring(0, 13) + "…" : text; g.drawString(t, x - fm.stringWidth(t)/2, y + fm.getAscent()/2); }
        private Color[] colors(String scheme) { return switch (scheme) { case "pastel" -> new Color[]{new Color(255,179,186),new Color(255,223,186),new Color(255,255,186),new Color(186,255,201),new Color(186,225,255)}; case "vivid" -> new Color[]{Color.RED,Color.ORANGE,Color.YELLOW,Color.GREEN,Color.CYAN,Color.MAGENTA}; case "mono" -> new Color[]{new Color(220,220,220),new Color(180,180,180),new Color(140,140,140),new Color(100,100,100)}; default -> new Color[]{new Color(66,135,245),new Color(245,166,35),new Color(126,211,33),new Color(189,16,224),new Color(80,227,194)}; }; }
    }
}
