import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * NOIZ - Music. Mood. Repeat.
 * Java 17 Swing music player.
 * MP3 support is provided by the mp3spi dependency in pom.xml.
 */
public class NOIZ extends JFrame {
    private static final Color BG = new Color(17, 17, 24);
    private static final Color PANEL = new Color(27, 27, 38);
    private static final Color CARD = new Color(39, 37, 53);
    private static final Color PURPLE = new Color(170, 140, 255);
    private static final Color PURPLE_DARK = new Color(91, 68, 139);
    private static final Color TEXT = new Color(245, 243, 250);
    private static final Color MUTED = new Color(166, 163, 180);
    private static final Path DATA_FILE = Path.of(
            System.getProperty("user.home"), ".noiz-library.dat");

    private static class Track implements Serializable {
        private static final long serialVersionUID = 1L;
        String path;
        String title;

        Track(File file) {
            path = file.getAbsolutePath();
            // Remove only the final file extension.
            title = file.getName().replaceFirst("\\.[^.]+$", "");
        }

        File file() { return new File(path); }

        @Override public String toString() { return title; }

        @Override public boolean equals(Object obj) {
            return obj instanceof Track other
                    && path.equalsIgnoreCase(other.path);
        }

        @Override public int hashCode() {
            return path.toLowerCase(Locale.ROOT).hashCode();
        }
    }

    private static class SavedData implements Serializable {
        private static final long serialVersionUID = 1L;
        ArrayList<Track> songs = new ArrayList<>();
        ArrayList<Track> recent = new ArrayList<>();
        ArrayList<Track> favourites = new ArrayList<>();
        LinkedHashMap<String, ArrayList<Track>> playlists = new LinkedHashMap<>();
    }

    private SavedData data = loadData();

    private final DefaultListModel<Track> listModel = new DefaultListModel<>();
    private final JList<Track> songList = new JList<>(listModel);
    private final JTextField search = new JTextField();
    private final JLabel sectionTitle = new JLabel("Your music");
    private final JLabel nowTitle = new JLabel("Nothing playing");
    private final JLabel nowTime = new JLabel("0:00 / 0:00");
    private final JButton playButton = new JButton("▶");
    private final JSlider progress = new JSlider(0, 1000, 0);
    private final JSlider volume = new JSlider(0, 100, 75);
    private final JLabel countLabel = new JLabel("0 songs");

    private Clip clip;
    private ArrayList<Track> queue = new ArrayList<>();
    private int current = -1;
    private String section = "Library";
    private String activePlaylist = null;
    private boolean shuffle = false;
    private boolean repeatAll = false;
    private boolean repeatOne = false;
    private boolean seeking = false;
    private boolean muted = false;
    private int previousVolume = 75;
    private boolean loadingTrack = false;
    private final Random random = new Random();
    private final javax.swing.Timer progressTimer;

    public NOIZ() {
        super("NOIZ — Music. Mood. Repeat.");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(900, 620));
        setSize(1120, 730);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout());

        add(buildSidebar(), BorderLayout.WEST);
        add(buildMainPanel(), BorderLayout.CENTER);
        add(buildPlayerPanel(), BorderLayout.SOUTH);

        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshList(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshList(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshList(); }
        });

        progressTimer = new javax.swing.Timer(250, e -> updateProgress());
        progressTimer.start();
        installShortcuts();
        refreshList();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                saveData();
                stopAudio();
                progressTimer.stop();
                dispose();
            }
        });
    }

    private JPanel buildSidebar() {
        JPanel panel = new JPanel();
        panel.setBackground(PANEL);
        panel.setPreferredSize(new Dimension(220, 0));
        panel.setBorder(new EmptyBorder(26, 16, 20, 16));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JLabel logo = new JLabel("NOIZ");
        logo.setForeground(PURPLE);
        logo.setFont(new Font("Segoe UI", Font.BOLD, 29));
        JLabel tagline = new JLabel("MUSIC. MOOD. REPEAT.");
        tagline.setForeground(MUTED);
        tagline.setFont(new Font("Segoe UI", Font.BOLD, 10));
        panel.add(logo);
        panel.add(Box.createVerticalStrut(5));
        panel.add(tagline);
        panel.add(Box.createVerticalStrut(38));

        addNav(panel, "HOME", () -> {
            section = "Home";
            activePlaylist = null;
            refreshList();
        });
        addNav(panel, "MY LIBRARY", () -> {
            section = "Library";
            activePlaylist = null;
            refreshList();
        });
        addNav(panel, "PLAYLISTS", this::choosePlaylist);
        addNav(panel, "RECENTLY PLAYED", () -> {
            section = "Recent";
            activePlaylist = null;
            refreshList();
        });
        addNav(panel, "FAVOURITES", () -> {
            section = "Favourites";
            activePlaylist = null;
            refreshList();
        });

        panel.add(Box.createVerticalStrut(18));
        addNav(panel, "ADD MUSIC", this::importMusic);
        addNav(panel, "MANAGE PLAYLISTS", this::managePlaylists);
        panel.add(Box.createVerticalGlue());

        JLabel footer = new JLabel("YOUR SOUND, YOUR SPACE");
        footer.setForeground(MUTED);
        footer.setFont(new Font("Segoe UI", Font.BOLD, 9));
        panel.add(footer);
        return panel;
    }

    private void addNav(JPanel panel, String text, Runnable action) {
        JButton button = new JButton(text);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        styleButton(button, PANEL, TEXT);
        button.setFont(new Font("Segoe UI", Font.BOLD, 13));
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setBorder(new EmptyBorder(12, 12, 12, 9));
        button.setMaximumSize(new Dimension(195, 46));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.addActionListener(e -> action.run());
        panel.add(button);
    }

    private JPanel buildMainPanel() {
        JPanel panel = new JPanel(new BorderLayout(12, 18));
        panel.setBackground(BG);
        panel.setBorder(new EmptyBorder(25, 27, 18, 27));

        JPanel top = new JPanel(new BorderLayout(15, 0));
        top.setOpaque(false);
        sectionTitle.setForeground(TEXT);
        sectionTitle.setFont(new Font("Segoe UI", Font.BOLD, 27));
        top.add(sectionTitle, BorderLayout.WEST);

        JPanel searchPanel = new JPanel(new BorderLayout(7, 0));
        searchPanel.setOpaque(false);
        search.setPreferredSize(new Dimension(290, 40));
        search.setBackground(PANEL);
        search.setForeground(TEXT);
        search.setCaretColor(PURPLE);
        search.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        search.setToolTipText("Search songs by title or file path");
        search.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CARD),
                new EmptyBorder(9, 12, 9, 12)));
        JButton clearSearch = smallButton("CLEAR");
        clearSearch.setToolTipText("Clear search");
        clearSearch.addActionListener(e -> search.setText(""));
        searchPanel.add(search, BorderLayout.CENTER);
        searchPanel.add(clearSearch, BorderLayout.EAST);
        top.add(searchPanel, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 14));
        body.setOpaque(false);

        JPanel hero = new JPanel(new BorderLayout(15, 0));
        hero.setBackground(new Color(48, 39, 70));
        hero.setBorder(new EmptyBorder(21, 23, 21, 23));
        JPanel heroText = new JPanel();
        heroText.setOpaque(false);
        heroText.setLayout(new BoxLayout(heroText, BoxLayout.Y_AXIS));

        JLabel eyebrow = new JLabel("YOUR SOUND, YOUR SPACE");
        eyebrow.setForeground(new Color(207, 190, 255));
        eyebrow.setFont(new Font("Segoe UI", Font.BOLD, 10));
        JLabel headline = new JLabel("Good music. Good mood.");
        headline.setForeground(TEXT);
        headline.setFont(new Font("Segoe UI", Font.BOLD, 24));
        JLabel sub = new JLabel("Bring your favourite tracks together.");
        sub.setForeground(new Color(198, 190, 213));
        sub.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        heroText.add(eyebrow);
        heroText.add(Box.createVerticalStrut(7));
        heroText.add(headline);
        heroText.add(Box.createVerticalStrut(5));
        heroText.add(sub);

        JButton addMusic = primaryButton("ADD MUSIC");
        addMusic.addActionListener(e -> importMusic());
        hero.add(heroText, BorderLayout.CENTER);
        hero.add(addMusic, BorderLayout.EAST);
        body.add(hero, BorderLayout.NORTH);

        songList.setBackground(PANEL);
        songList.setForeground(TEXT);
        songList.setSelectionBackground(PURPLE_DARK);
        songList.setSelectionForeground(Color.WHITE);
        songList.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        songList.setFixedCellHeight(45);
        songList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, selected, focus);
                label.setBorder(new EmptyBorder(8, 14, 8, 14));
                label.setBackground(selected ? PURPLE_DARK : PANEL);
                label.setForeground(TEXT);
                return label;
            }
        });
        songList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) playSelected();
            }
        });

        JScrollPane scroll = new JScrollPane(songList);
        scroll.setBorder(BorderFactory.createLineBorder(CARD));
        scroll.getViewport().setBackground(PANEL);
        body.add(scroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        countLabel.setForeground(MUTED);
        countLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        bottom.add(countLabel, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton playSelected = primaryButton("PLAY SELECTED");
        playSelected.addActionListener(e -> playSelected());
        JButton favourite = secondaryButton("FAVOURITE");
        favourite.addActionListener(e -> toggleFavourite());
        JButton create = secondaryButton("NEW PLAYLIST");
        create.addActionListener(e -> createPlaylist());
        JButton addTo = secondaryButton("ADD TO PLAYLIST");
        addTo.addActionListener(e -> addToPlaylist());
        JButton remove = secondaryButton("REMOVE");
        remove.addActionListener(e -> removeSelected());
        actions.add(playSelected);
        actions.add(favourite);
        actions.add(create);
        actions.add(addTo);
        actions.add(remove);
        bottom.add(actions, BorderLayout.EAST);
        body.add(bottom, BorderLayout.SOUTH);

        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildPlayerPanel() {
        JPanel panel = new JPanel(new BorderLayout(18, 0));
        panel.setBackground(PANEL);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, CARD),
                new EmptyBorder(13, 19, 13, 19)));
        panel.setPreferredSize(new Dimension(0, 105));

        JPanel info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        nowTitle.setForeground(TEXT);
        nowTitle.setFont(new Font("Segoe UI", Font.BOLD, 13));
        nowTitle.setMaximumSize(new Dimension(220, 24));
        JLabel artist = new JLabel("Local music library");
        artist.setForeground(MUTED);
        artist.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        info.add(nowTitle);
        info.add(Box.createVerticalStrut(5));
        info.add(artist);
        info.setPreferredSize(new Dimension(205, 55));
        panel.add(info, BorderLayout.WEST);

        JPanel centre = new JPanel();
        centre.setOpaque(false);
        centre.setLayout(new BoxLayout(centre, BoxLayout.Y_AXIS));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        controls.setOpaque(false);

        JButton shuffleButton = secondaryButton("SHUFFLE: OFF");
        shuffleButton.addActionListener(e -> {
            shuffle = !shuffle;
            shuffleButton.setText(shuffle ? "SHUFFLE: ON" : "SHUFFLE: OFF");
            styleButton(shuffleButton, shuffle ? PURPLE_DARK : CARD, TEXT);
        });

        JButton previous = secondaryButton("PREVIOUS");
        previous.addActionListener(e -> previous());

        playButton.setText("PLAY / PAUSE");
        styleButton(playButton, PURPLE, new Color(20, 16, 29));
        playButton.setFont(new Font("Segoe UI", Font.BOLD, 11));
        playButton.setBorder(new EmptyBorder(10, 16, 10, 16));
        playButton.addActionListener(e -> togglePlay());

        JButton next = secondaryButton("NEXT");
        next.addActionListener(e -> next());

        JButton repeatButton = secondaryButton("REPEAT: OFF");
        repeatButton.addActionListener(e -> {
            if (!repeatAll && !repeatOne) {
                repeatAll = true;
                repeatButton.setText("REPEAT: ALL");
            } else if (repeatAll) {
                repeatAll = false;
                repeatOne = true;
                repeatButton.setText("REPEAT: ONE");
            } else {
                repeatOne = false;
                repeatButton.setText("REPEAT: OFF");
            }
        });

        controls.add(shuffleButton);
        controls.add(previous);
        controls.add(playButton);
        controls.add(next);
        controls.add(repeatButton);
        centre.add(controls);

        JPanel seek = new JPanel(new BorderLayout(10, 0));
        seek.setOpaque(false);
        progress.setOpaque(false);
        progress.setForeground(PURPLE);
        progress.setToolTipText("Drag to seek");
        progress.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { seeking = true; }
            @Override public void mouseReleased(MouseEvent e) {
                seeking = false;
                if (clip != null && clip.isOpen() && clip.getMicrosecondLength() > 0) {
                    long target = clip.getMicrosecondLength() * progress.getValue() / 1000;
                    clip.setMicrosecondPosition(target);
                }
            }
        });
        seek.add(progress, BorderLayout.CENTER);
        nowTime.setForeground(MUTED);
        nowTime.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        seek.add(nowTime, BorderLayout.EAST);
        centre.add(Box.createVerticalStrut(7));
        centre.add(seek);
        panel.add(centre, BorderLayout.CENTER);

        JPanel volumePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 10));
        volumePanel.setOpaque(false);
        JLabel volumeLabel = new JLabel("VOL");
        volumeLabel.setForeground(MUTED);
        volumeLabel.setFont(new Font("Segoe UI", Font.BOLD, 10));
        volume.setPreferredSize(new Dimension(95, 20));
        volume.setOpaque(false);
        volume.setForeground(PURPLE);
        volume.addChangeListener(e -> {
            if (volume.getValue() > 0) muted = false;
            setVolume(volume.getValue());
        });
        JButton muteButton = secondaryButton("MUTE");
        muteButton.setToolTipText("Mute / unmute");
        muteButton.addActionListener(e -> {
            if (!muted) {
                previousVolume = Math.max(1, volume.getValue());
                muted = true;
                volume.setValue(0);
            } else {
                muted = false;
                volume.setValue(previousVolume);
            }
        });
        volumePanel.add(volumeLabel);
        volumePanel.add(volume);
        volumePanel.add(muteButton);
        panel.add(volumePanel, BorderLayout.EAST);
        return panel;
    }

    private void styleButton(JButton button, Color background, Color foreground) {
        button.setBackground(background);
        button.setForeground(foreground);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        button.setFocusable(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private JButton primaryButton(String text) {
        JButton button = new JButton(text);
        styleButton(button, PURPLE, new Color(20, 16, 29));
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setBorder(new EmptyBorder(11, 16, 11, 16));
        return button;
    }

    private JButton secondaryButton(String text) {
        JButton button = new JButton(text);
        styleButton(button, CARD, TEXT);
        button.setFont(new Font("Segoe UI", Font.BOLD, 11));
        button.setBorder(new EmptyBorder(10, 13, 10, 13));
        return button;
    }

    private JButton smallButton(String text) {
        JButton button = secondaryButton(text);
        button.setFont(new Font("Segoe UI", Font.BOLD, 17));
        button.setPreferredSize(new Dimension(42, 40));
        return button;
    }

    private void installShortcuts() {
        JRootPane root = getRootPane();
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke('O', mask), "importMusic");
        root.getActionMap().put("importMusic", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { importMusic(); }
        });
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("SPACE"), "playPause");
        root.getActionMap().put("playPause", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { togglePlay(); }
        });
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("RIGHT"), "nextTrack");
        root.getActionMap().put("nextTrack", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { next(); }
        });
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("LEFT"), "previousTrack");
        root.getActionMap().put("previousTrack", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { previous(); }
        });
    }

    private boolean isAudioFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return file.isFile() && (name.endsWith(".mp3")
                || name.endsWith(".wav") || name.endsWith(".aiff")
                || name.endsWith(".aif") || name.endsWith(".au"));
    }

    private void importMusic() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Add music to NOIZ");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Audio files (MP3, WAV, AIFF, AU)",
                "mp3", "wav", "aiff", "aif", "au"));

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File[] selected = chooser.getSelectedFiles();
            int added = 0;
            for (File file : selected) {
                if (isAudioFile(file)) {
                    Track track = new Track(file);
                    if (!data.songs.contains(track)) {
                        data.songs.add(track);
                        added++;
                    }
                }
            }
            saveData();
            section = "Library";
            activePlaylist = null;
            refreshList();
            showMessage(added + " song(s) added to your library.");
        }
    }

    private ArrayList<Track> getCurrentSource() {
        switch (section) {
            case "Recent": return data.recent;
            case "Favourites": return data.favourites;
            case "Playlist":
                if (activePlaylist != null && data.playlists.containsKey(activePlaylist)) {
                    return data.playlists.get(activePlaylist);
                }
                return queue;
            default: return data.songs;
        }
    }

    private void refreshList() {
        listModel.clear();
        String query = search.getText().toLowerCase(Locale.ROOT).trim();
        ArrayList<Track> source = getCurrentSource();

        switch (section) {
            case "Home": sectionTitle.setText("Good music. Good mood."); break;
            case "Recent": sectionTitle.setText("Recently Played"); break;
            case "Favourites": sectionTitle.setText("Favourites ♥"); break;
            case "Playlist":
                sectionTitle.setText(activePlaylist == null ? "Playlist" : activePlaylist);
                break;
            default: sectionTitle.setText("My Library");
        }

        int visible = 0;
        for (Track track : new ArrayList<>(source)) {
            if (track == null || track.path == null) continue;
            String searchable = (track.title + " " + track.path).toLowerCase(Locale.ROOT);
            if (searchable.contains(query)) {
                listModel.addElement(track);
                visible++;
            }
        }
        countLabel.setText(visible + (visible == 1 ? " song" : " songs"));
    }

    private void choosePlaylist() {
        if (data.playlists.isEmpty()) {
            showMessage("No playlists yet. Create one using + Playlist.");
            return;
        }
        String[] names = data.playlists.keySet().toArray(new String[0]);
        String selected = (String) JOptionPane.showInputDialog(
                this, "Choose a playlist to open:", "NOIZ Playlists",
                JOptionPane.PLAIN_MESSAGE, null, names, names[0]);
        if (selected != null) {
            section = "Playlist";
            activePlaylist = selected;
            refreshList();
        }
    }

    private void createPlaylist() {
        String name = JOptionPane.showInputDialog(this, "Enter playlist name:");
        if (name == null) return;
        name = name.trim();
        if (name.isEmpty()) {
            showMessage("Playlist name cannot be empty.");
            return;
        }
        if (data.playlists.containsKey(name)) {
            showMessage("A playlist with that name already exists.");
            return;
        }
        data.playlists.put(name, new ArrayList<>());
        saveData();
        section = "Playlist";
        activePlaylist = name;
        refreshList();
        showMessage("Playlist created: " + name);
    }

    private void addToPlaylist() {
        Track track = songList.getSelectedValue();
        if (track == null) {
            showMessage("Select a song first.");
            return;
        }
        if (data.playlists.isEmpty()) {
            showMessage("Create a playlist first.");
            return;
        }
        String[] names = data.playlists.keySet().toArray(new String[0]);
        String selected = (String) JOptionPane.showInputDialog(
                this, "Add \"" + track.title + "\" to:", "Add to playlist",
                JOptionPane.PLAIN_MESSAGE, null, names, names[0]);
        if (selected != null) {
            ArrayList<Track> tracks = data.playlists.get(selected);
            if (!tracks.contains(track)) {
                tracks.add(track);
                saveData();
                showMessage("Added to " + selected + ".");
            } else {
                showMessage("This song is already in " + selected + ".");
            }
        }
    }

    private void managePlaylists() {
        if (data.playlists.isEmpty()) {
            showMessage("You have no playlists yet. Create one first.");
            return;
        }
        String[] names = data.playlists.keySet().toArray(new String[0]);
        String selected = (String) JOptionPane.showInputDialog(
                this, "Select a playlist to manage:", "Manage playlists",
                JOptionPane.PLAIN_MESSAGE, null, names, names[0]);
        if (selected == null) return;

        Object[] options = {"Open", "Rename", "Delete", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this,
                selected + "  •  " + data.playlists.get(selected).size() + " songs",
                "Manage playlist", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[0]);

        if (choice == 0) {
            section = "Playlist";
            activePlaylist = selected;
            refreshList();
        } else if (choice == 1) {
            renamePlaylist(selected);
        } else if (choice == 2) {
            deletePlaylist(selected);
        }
    }

    private void renamePlaylist(String oldName) {
        String newName = JOptionPane.showInputDialog(
                this, "Enter new playlist name:", oldName);
        if (newName == null) return;
        newName = newName.trim();
        if (newName.isEmpty() || (!newName.equals(oldName)
                && data.playlists.containsKey(newName))) {
            showMessage("Enter a unique, non-empty playlist name.");
            return;
        }
        if (newName.equals(oldName)) return;

        LinkedHashMap<String, ArrayList<Track>> renamed = new LinkedHashMap<>();
        for (Map.Entry<String, ArrayList<Track>> entry : data.playlists.entrySet()) {
            renamed.put(entry.getKey().equals(oldName) ? newName : entry.getKey(),
                    entry.getValue());
        }
        data.playlists = renamed;
        if (oldName.equals(activePlaylist)) activePlaylist = newName;
        saveData();
        refreshList();
    }

    private void deletePlaylist(String name) {
        int answer = JOptionPane.showConfirmDialog(this,
                "Delete playlist \"" + name + "\"? Songs in your library will not be deleted.",
                "Confirm delete", JOptionPane.YES_NO_OPTION);
        if (answer == JOptionPane.YES_OPTION) {
            data.playlists.remove(name);
            if (name.equals(activePlaylist)) {
                activePlaylist = null;
                section = "Library";
            }
            saveData();
            refreshList();
            showMessage("Playlist deleted.");
        }
    }

    private void toggleFavourite() {
        Track track = songList.getSelectedValue();
        if (track == null) {
            showMessage("Select a song first.");
            return;
        }
        if (data.favourites.contains(track)) {
            data.favourites.remove(track);
            showMessage("Removed from favourites.");
        } else {
            data.favourites.add(track);
            showMessage("Added to favourites ♥");
        }
        saveData();
        if (section.equals("Favourites")) refreshList();
    }

    private void removeSelected() {
        Track track = songList.getSelectedValue();
        if (track == null) {
            showMessage("Select a song first.");
            return;
        }

        if ("Playlist".equals(section) && activePlaylist != null) {
            data.playlists.get(activePlaylist).remove(track);
        } else if ("Favourites".equals(section)) {
            data.favourites.remove(track);
        } else {
            int answer = JOptionPane.showConfirmDialog(this,
                    "Remove \"" + track.title + "\" from your NOIZ library?",
                    "Remove song", JOptionPane.YES_NO_OPTION);
            if (answer != JOptionPane.YES_OPTION) return;
            data.songs.remove(track);
            data.recent.remove(track);
            data.favourites.remove(track);
            for (ArrayList<Track> playlist : data.playlists.values()) {
                playlist.remove(track);
            }
            queue.remove(track);
            if (queue.isEmpty()) current = -1;
        }
        saveData();
        refreshList();
    }

    private void playSelected() {
        Track selected = songList.getSelectedValue();
        if (selected == null) {
            showMessage("Select a song to play.");
            return;
        }

        queue = new ArrayList<>(getCurrentSource());
        current = queue.indexOf(selected);
        if (current >= 0) startCurrent();
    }

    private void startCurrent() {
        if (current < 0 || current >= queue.size() || loadingTrack) return;
        Track track = queue.get(current);
        if (!track.file().isFile()) {
            showMessage("File not found:\n" + track.path);
            return;
        }

        stopAudio();
        loadingTrack = true;
        nowTitle.setText(track.title);
        nowTime.setText("Loading...");
        progress.setValue(0);

        data.recent.remove(track);
        data.recent.add(0, track);
        while (data.recent.size() > 30) data.recent.remove(data.recent.size() - 1);
        saveData();

        Thread loader = new Thread(() -> {
            Clip newClip = null;
            try (AudioInputStream original = AudioSystem.getAudioInputStream(track.file())) {
                AudioInputStream playable = original;
                AudioFormat format = original.getFormat();

                if (format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                        || format.getSampleSizeInBits() != 16) {
                    AudioFormat pcm = new AudioFormat(
                            AudioFormat.Encoding.PCM_SIGNED,
                            format.getSampleRate(), 16, format.getChannels(),
                            format.getChannels() * 2, format.getSampleRate(), false);
                    playable = AudioSystem.getAudioInputStream(pcm, original);
                }

                newClip = AudioSystem.getClip();
                newClip.open(playable);
                if (playable != original) playable.close();

                Clip readyClip = newClip;
                SwingUtilities.invokeLater(() -> {
                    loadingTrack = false;
                    clip = readyClip;
                    applyVolume();
                    readyClip.addLineListener(event -> {
                        if (event.getType() == LineEvent.Type.STOP
                                && readyClip.isOpen()
                                && readyClip.getMicrosecondLength() > 0
                                && readyClip.getMicrosecondPosition()
                                   >= readyClip.getMicrosecondLength() - 100_000
                                && clip == readyClip) {
                            SwingUtilities.invokeLater(this::next);
                        }
                    });
                    readyClip.start();
                    playButton.setText("PAUSE");
                    nowTime.setText("0:00 / " + formatTime(readyClip.getMicrosecondLength()));
                });
            } catch (Exception ex) {
                if (newClip != null) newClip.close();
                SwingUtilities.invokeLater(() -> {
                    loadingTrack = false;
                    playButton.setText("PLAY / PAUSE");
                    nowTime.setText("0:00 / 0:00");
                    showMessage("NOIZ could not play this file.\n"
                            + "Check that the file is a supported MP3/WAV audio file.\n\n"
                            + ex.getMessage());
                });
            }
        }, "NOIZ-audio-loader");
        loader.setDaemon(true);
        loader.start();
    }

    private void togglePlay() {
        if (loadingTrack) return;
        if (clip == null || !clip.isOpen()) {
            if (!queue.isEmpty() && current >= 0) {
                startCurrent();
            } else if (!data.songs.isEmpty()) {
                queue = new ArrayList<>(data.songs);
                current = 0;
                startCurrent();
            } else {
                showMessage("Your library is empty. Click Add Music to import songs.");
            }
            return;
        }
        if (clip.isRunning()) {
            clip.stop();
            playButton.setText("PLAY / PAUSE");
        } else {
            clip.start();
            playButton.setText("PAUSE");
        }
    }

    private void next() {
        if (queue.isEmpty() || loadingTrack) return;
        if (repeatOne && clip != null && clip.isOpen()) {
            clip.setMicrosecondPosition(0);
            clip.start();
            playButton.setText("PAUSE");
            return;
        }

        if (shuffle && queue.size() > 1) {
            int old = current;
            do {
                current = random.nextInt(queue.size());
            } while (current == old);
        } else if (current < queue.size() - 1) {
            current++;
        } else if (repeatAll) {
            current = 0;
        } else {
            stopAudio();
            nowTime.setText("0:00 / 0:00");
            return;
        }
        startCurrent();
    }

    private void previous() {
        if (loadingTrack) return;
        if (clip != null && clip.isOpen()
                && clip.getMicrosecondPosition() > 3_000_000) {
            clip.setMicrosecondPosition(0);
            return;
        }
        if (!queue.isEmpty()) {
            if (current > 0) current--;
            else if (repeatAll) current = queue.size() - 1;
            startCurrent();
        }
    }

    private void stopAudio() {
        Clip old = clip;
        clip = null;
        if (old != null) {
            old.stop();
            old.close();
        }
        playButton.setText("PLAY / PAUSE");
    }

    private void setVolume(int value) {
        if (clip == null || !clip.isOpen()
                || !clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
        FloatControl control = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
        float db = value == 0 ? control.getMinimum()
                : (float) (20 * Math.log10(value / 100.0));
        control.setValue(Math.max(control.getMinimum(),
                Math.min(control.getMaximum(), db)));
    }

    private void applyVolume() {
        setVolume(muted ? 0 : volume.getValue());
    }

    private void updateProgress() {
        if (clip == null || !clip.isOpen()) return;
        long duration = clip.getMicrosecondLength();
        long position = clip.getMicrosecondPosition();
        if (!seeking && duration > 0) {
            progress.setValue((int) (position * 1000 / duration));
        }
        nowTime.setText(formatTime(position) + " / " + formatTime(duration));
    }

    private String formatTime(long micros) {
        long seconds = Math.max(0, micros / 1_000_000);
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }

    private void showMessage(String message) {
        JOptionPane.showMessageDialog(this, message, "NOIZ",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private SavedData loadData() {
        if (Files.exists(DATA_FILE)) {
            try (ObjectInputStream input =
                         new ObjectInputStream(Files.newInputStream(DATA_FILE))) {
                Object saved = input.readObject();
                if (saved instanceof SavedData) return (SavedData) saved;
            } catch (Exception ex) {
                System.err.println("Could not load NOIZ library: " + ex.getMessage());
            }
        }
        return new SavedData();
    }

    private void saveData() {
        try {
            Files.createDirectories(DATA_FILE.getParent());
            try (ObjectOutputStream output =
                         new ObjectOutputStream(Files.newOutputStream(DATA_FILE))) {
                output.writeObject(data);
            }
        } catch (IOException ex) {
            showMessage("Could not save your library:\n" + ex.getMessage());
        }
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            UIManager.put("Button.background", CARD);
            UIManager.put("Button.foreground", TEXT);
            UIManager.put("Button.select", PURPLE_DARK);
            UIManager.put("Button.focus", new Color(0, 0, 0, 0));
        } catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> new NOIZ().setVisible(true));
    }
}
