import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMultipart;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

public class Main {
  record Account(
      int index, String host, int port, String user, String password, String inboxName) {}

  static class AccountConnection {
    private final Account account;
    private final Properties properties = new Properties();
    private Store emailStore;
    private Folder emailFolder;

    public AccountConnection(Account account) {
      this.account = account;
      properties.put("mail.store.protocol", "imap");
      properties.put("mail.imap.ssl.enable", true);
      properties.put("mail.imap.host", account.host());
      properties.put("mail.imap.port", account.port());
    }

    public List<Message> getMessages() throws Exception {
      if (emailStore == null || emailFolder == null) {
        emailStore = Session.getInstance(properties).getStore();
        emailStore.connect(account.user(), account.password());
        emailFolder = emailStore.getFolder(account.inboxName());
        emailFolder.open(Folder.READ_ONLY);
      }
      List<Message> l = new ArrayList<>();
      int c1 = emailFolder.getMessageCount();
      int c2 = 0;
      for (int i = c1; i > 0 && c2 < max_mails; i--, c2++) {
        l.add(emailFolder.getMessage(i));
      }
      return l;
    }

    public void close() throws Exception {
      System.out.println("Closing connection to: " + account.host());
      if (emailFolder != null) {
        emailFolder.close();
        emailFolder = null;
      }
      if (emailStore != null) {
        emailStore.close();
        emailStore = null;
      }
    }
  }

  static class AccountManager {
    private static final List<Account> accounts = getAccounts();

    private final List<AccountConnection> connections = new ArrayList<>();

    public AccountManager() {
      for (Account account : accounts) {
        connections.add(new AccountConnection(account));
      }
    }

    public int getAccountCount() {
      return accounts.size();
    }

    public Map<Account, List<Message>> getMessages() throws Exception {
      Map<Account, List<Message>> messages = new LinkedHashMap<>();
      for (AccountConnection ac : connections) {
        messages.put(ac.account, ac.getMessages());
      }
      return messages;
    }

    public void close() throws Exception {
      for (AccountConnection ac : connections) {
        ac.close();
      }
    }

    private static List<Account> getAccounts() {
      String path = "accounts.conf";
      File file = new File(path);
      if (!file.exists()) {
        try (PrintWriter pw = new PrintWriter(file, StandardCharsets.UTF_8)) {
          // Adjust your accounts configuration here or in the accounts.conf file
          pw.println(
              """
              {
                 "accounts":[
                    {
                       "index":1,
                       "host":"foo",
                       "port":993,
                       "user":"foo",
                       "password":"foo",
                       "inboxName":"INBOX"
                    },
                    {
                       "index":2,
                       "host":"foo",
                       "port":993,
                       "user":"foo",
                       "password":"foo",
                       "inboxName":"INBOX"
                    }
                 ]
              }
              """);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
      Config config = ConfigFactory.parseFile(file);
      List<Account> l = new ArrayList<>();
      for (Config c : config.getConfigList("accounts")) {
        l.add(
            new Account(
                c.getInt("index"),
                c.getString("host"),
                c.getInt("port"),
                c.getString("user"),
                c.getString("password"),
                c.getString("inboxName")));
      }
      return l;
    }
  }

  static class MultiLineTableModel extends AbstractTableModel {
    private final List<String[]> data = new ArrayList<>();

    public void addRow(Message message) throws Exception {
      data.add(new String[] {getDate(message), getFrom(message), getSubject(message)});
      fireTableDataChanged();
    }

    public void clearRows() {
      data.clear();
      fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
      return data.size();
    }

    @Override
    public int getColumnCount() {
      return 1;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
      return data.get(rowIndex);
    }

    @Override
    public String getColumnName(int column) {
      return "Messages";
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
      return String[].class;
    }
  }

  static class MultiLineTableCellRenderer extends JList<String> implements TableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(
        JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      if (value instanceof String[]) {
        setListData((String[]) value);
      }

      if (isSelected) {
        setBackground(UIManager.getColor("Table.selectionBackground"));
      } else {
        setBackground(UIManager.getColor("Table.background"));
      }

      return this;
    }
  }

  static class MultilineTable {
    private final MultiLineTableModel model = new MultiLineTableModel();
    private final JTable table = new JTable(model);
    private int lastIndex = -1;
    private long lastIndexTime = -1;

    public MultilineTable() {
      table.setDefaultRenderer(String[].class, new MultiLineTableCellRenderer());
      table.setRowHeight((table.getRowHeight() + 3) * 3);
      table
          .getSelectionModel()
          .addListSelectionListener(
              e -> {
                if (!e.getValueIsAdjusting()) {
                  updateLastIndex();
                }
              });
    }

    private void updateLastIndex() {
      lastIndex = table.getSelectedRow();
      lastIndexTime = System.currentTimeMillis();
    }
  }

  private static Map<Account, List<Message>> messages = new LinkedHashMap<>();
  private static volatile int max_mails = 6;

  private static String getDate(Message message) throws Exception {
    return message.getSentDate().toString();
  }

  private static String getFrom(Message message) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (Address a : message.getFrom()) {
      sb.append(a.toString());
    }
    return sb.toString();
  }

  private static String getSubject(Message message) throws Exception {
    return message.getSubject();
  }

  private static String getContent(Message message) throws Exception {
    StringBuilder sb = new StringBuilder();
    if (message.isMimeType("text/plain")) {
      sb.append(message.getContent().toString());
    }
    if (message.isMimeType("multipart/*")) {
      MimeMultipart mimeMultipart = (MimeMultipart) message.getContent();
      for (int i = 0; i < mimeMultipart.getCount(); i++) {
        Document document = Jsoup.parse(mimeMultipart.getBodyPart(i).getContent().toString());
        document.outputSettings(new Document.OutputSettings().prettyPrint(false));
        document.select("br").append("\\n");
        document.select("p").prepend("\\n\\n");
        String s = document.html().replaceAll("\\\\n", "\n");
        sb.append(
            Jsoup.clean(s, "", Safelist.none(), new Document.OutputSettings().prettyPrint(false)));
      }
    }
    return sb.toString();
  }

  private static void createGUI() {
    AccountManager am = new AccountManager();

    JButton reloadButton = new JButton("Reload");
    JTextField maxField = new JTextField(Integer.toString(max_mails));
    maxField.setPreferredSize(new Dimension(30, 22));
    JButton displayButton = new JButton("Display");

    MultilineTable[] multilineTables = new MultilineTable[am.getAccountCount()];
    for (int i = 0; i < multilineTables.length; i++) {
      multilineTables[i] = new MultilineTable();
    }

    JTextArea area = new JTextArea();
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setEditable(false);
    area.setFont(new Font("Monospaced", Font.PLAIN, 12));

    JPanel bp1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
    bp1.add(reloadButton);
    bp1.add(maxField);
    JPanel bp2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
    bp2.add(displayButton);
    JPanel p1 = new JPanel(new GridLayout(1, 2));
    p1.add(bp1);
    p1.add(bp2);
    JPanel tablesPanel = new JPanel(new GridLayout(multilineTables.length, 1));
    for (MultilineTable t : multilineTables) {
      tablesPanel.add(new JScrollPane(t.table));
    }
    JPanel p2 = new JPanel(new GridLayout(1, 2));
    p2.add(tablesPanel);
    p2.add(new JScrollPane(area));
    JFrame f = new JFrame("MMClient");
    f.setLayout(new BorderLayout());
    f.add(p1, BorderLayout.NORTH);
    f.add(p2, BorderLayout.CENTER);
    f.setSize(1200, 850);
    f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    f.setVisible(true);

    maxField
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              public void changedUpdate(DocumentEvent e) {
                update();
              }

              public void removeUpdate(DocumentEvent e) {
                update();
              }

              public void insertUpdate(DocumentEvent e) {
                update();
              }

              public void update() {
                String s = maxField.getText();
                if (!s.isBlank()) {
                  try {
                    max_mails = Integer.parseInt(s);
                  } catch (NumberFormatException ignore) {
                  }
                }
              }
            });
    reloadButton.addActionListener(
        e -> {
          try {
            messages = am.getMessages();
            int i = 0;
            for (Map.Entry<Account, List<Message>> entry : messages.entrySet()) {
              multilineTables[i].model.clearRows();
              for (Message m : entry.getValue()) {
                multilineTables[i].model.addRow(m);
              }
              i++;
            }
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
    displayButton.addActionListener(
        e -> {
          try {
            int i = -1;
            int j = -1;
            long t = -1;
            for (int k = 0; k < multilineTables.length; k++) {
              MultilineTable mul = multilineTables[k];
              if (mul.lastIndex >= 0 && mul.lastIndexTime > t) {
                i = k;
                j = mul.lastIndex;
                t = mul.lastIndexTime;
              }
            }
            if (i >= 0) {
              Message m = messages.get(am.connections.get(i).account).get(j);
              area.setText(
                  getDate(m)
                      + "\n\n"
                      + getFrom(m)
                      + "\n\n"
                      + getSubject(m)
                      + "\n\n"
                      + getContent(m));
              area.setCaretPosition(0);
            }
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });

    f.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            try {
              am.close();
            } catch (Exception ex) {
              throw new RuntimeException(ex);
            }
          }
        });
  }

  public static void main(String[] args) {
    try {
      SwingUtilities.invokeLater(Main::createGUI);
    } catch (Exception e) {
      JOptionPane.showMessageDialog(
          null,
          e.getMessage() + "\n\n" + Arrays.toString(e.getStackTrace()),
          "Error",
          JOptionPane.ERROR_MESSAGE);
    }
  }
}
