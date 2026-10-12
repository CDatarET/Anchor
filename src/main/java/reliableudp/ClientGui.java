package reliableudp;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import reliableudp.metrics.MetricsLogger;
import reliableudp.protocol.ProtocolConfig;
import reliableudp.simulation.NetworkConditions;
import reliableudp.transport.TransferResult;
import reliableudp.util.Logger;

/**
 * Optional Swing front-end. It contains no protocol logic: it only collects settings and calls
 * {@link Client#transfer}; the log pane is fed by a {@link Logger} sink.
 */
public final class ClientGui {

    private ClientGui() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ClientGui::build);
    }

    private static void build() {
        JFrame frame = new JFrame("Reliable UDP client");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JTextField host = new JTextField("localhost");
        JTextField port = new JTextField("5000");
        JTextField file = new JTextField();
        JTextField chunk = new JTextField("1024");
        JTextField window = new JTextField("16");
        JTextField timeout = new JTextField("200");
        JTextField loss = new JTextField("0");
        JTextField delay = new JTextField("0");
        JTextField jitter = new JTextField("0");
        JTextField seed = new JTextField("1");
        JButton browse = new JButton("Browse...");
        JButton start = new JButton("Send file");
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        JTextArea log = new JTextArea(18, 80);
        log.setEditable(false);

        JPanel form = new JPanel(new GridLayout(0, 4, 6, 4));
        form.add(new JLabel("Server IP")); form.add(host);
        form.add(new JLabel("UDP port")); form.add(port);
        form.add(new JLabel("Chunk (bytes)")); form.add(chunk);
        form.add(new JLabel("Window (packets)")); form.add(window);
        form.add(new JLabel("Timeout (ms)")); form.add(timeout);
        form.add(new JLabel("Sim. loss (0..1)")); form.add(loss);
        form.add(new JLabel("Sim. delay (ms)")); form.add(delay);
        form.add(new JLabel("Sim. jitter (ms)")); form.add(jitter);
        form.add(new JLabel("Seed")); form.add(seed);

        JPanel filePanel = new JPanel(new BorderLayout(6, 0));
        filePanel.add(new JLabel("File: "), BorderLayout.WEST);
        filePanel.add(file, BorderLayout.CENTER);
        filePanel.add(browse, BorderLayout.EAST);

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.add(form, BorderLayout.NORTH);
        top.add(filePanel, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(start);
        top.add(buttons, BorderLayout.SOUTH);

        frame.add(top, BorderLayout.NORTH);
        frame.add(new JScrollPane(log), BorderLayout.CENTER);
        frame.add(bar, BorderLayout.SOUTH);

        Consumer<String> sink = line -> SwingUtilities.invokeLater(() -> {
            log.append(line + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
        Logger.addSink(sink);
        Logger.setConsoleEnabled(false);
        Logger.setLevel(Logger.Level.INFO);

        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                file.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        start.addActionListener(e -> {
            final InetSocketAddress server;
            final ProtocolConfig cfg;
            final NetworkConditions net;
            final Path path = Paths.get(file.getText().trim());
            try {
                server = new InetSocketAddress(InetAddress.getByName(host.getText().trim()),
                        Integer.parseInt(port.getText().trim()));
                cfg = new ProtocolConfig().chunkSize(Integer.parseInt(chunk.getText().trim()))
                        .windowSize(Integer.parseInt(window.getText().trim()))
                        .retransmitTimeoutMs(Integer.parseInt(timeout.getText().trim())).validate();
                net = new NetworkConditions(Double.parseDouble(loss.getText().trim()),
                        Integer.parseInt(delay.getText().trim()), Integer.parseInt(jitter.getText().trim()),
                        0, 0, Long.parseLong(seed.getText().trim()));
            } catch (Exception ex) {
                log.append("Invalid settings: " + ex.getMessage() + "\n");
                return;
            }
            start.setEnabled(false);
            bar.setValue(0);
            Thread worker = new Thread(() -> {
                TransferResult r = Client.transfer(server, path, cfg, net, "gui",
                        (acked, total) -> SwingUtilities.invokeLater(
                                () -> bar.setValue(total == 0 ? 100 : (int) (acked * 100 / total))));
                net.close();
                try {
                    MetricsLogger.append(Paths.get("logs/transfers.csv"), r.metrics());
                } catch (Exception ex) {
                    sink.accept("Could not write CSV: " + ex.getMessage());
                }
                SwingUtilities.invokeLater(() -> {
                    log.append(MetricsLogger.summary(r.metrics()) + "\n");
                    log.append("Statistics appended to logs/transfers.csv\n");
                    start.setEnabled(true);
                });
            }, "gui-transfer");
            worker.setDaemon(true);
            worker.start();
        });

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
