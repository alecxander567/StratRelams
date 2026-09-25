package com.cardgame;

import com.cardgame.common.AlertOverlay;
import com.cardgame.common.AudioManager;
import com.cardgame.common.ToastManager;
import com.cardgame.components.VolumeOptionsModal;
import com.cardgame.db.AchievementDao;
import com.cardgame.db.CollectionDao;
import com.cardgame.db.CurrentPlayer;
import com.cardgame.db.DatabaseManager;
import com.cardgame.db.FormationDao;
import com.cardgame.db.FormationJsonUtils;
import com.cardgame.db.GameStateSerializer;
import com.cardgame.db.MatchHistoryDao;
import com.cardgame.db.PlayerDao;
import com.cardgame.db.PlayerProfile;
import com.cardgame.db.SavedGameDao;
import com.cardgame.model.Achievement;
import com.cardgame.model.AchievementCategory;
import com.cardgame.model.AchievementEvaluator;
import com.cardgame.model.AchievementTracker;
import com.cardgame.model.CollectionCategory;
import com.cardgame.model.CollectionItem;
import com.cardgame.model.PlayerAchievements;
import com.cardgame.model.PlayerCollection;
import com.cardgame.model.PlayerSide;
import com.cardgame.model.PieceType;
import com.cardgame.model.Race;
import com.cardgame.model.Strategy;
import com.cardgame.net.NetworkTransport;
import com.cardgame.ui.*;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

public class App extends Application {

    private Stage stage;
    private Scene scene;
    private GameLayout gameLayout;
    private Race playerRace;
    private Race enemyRace;

    private Strategy redStrategy;
    private Strategy blueStrategy;

    private BattleScreen currentBattleScreen;
    private CountdownModal countdownModal;
    private PlayerSide playerSide = PlayerSide.RED;

    private NetworkTransport pendingTransport;
    private MatchmakingScreen matchmakingScreen;
    private MatchmakingScreen.MatchConfig pendingLanConfig;

    private PlayerProfile player;
    private PlayerDao playerDao;
    private CollectionDao collectionDao;
    private AchievementDao achievementDao;
    private MatchHistoryDao matchHistoryDao;

    private PlayerCollection playerCollection;
    private PlayerAchievements playerAchievements;

    private ToastManager toastManager;

    private Race currentPlayerRace;
    private Race currentEnemyRace;
    private int currentTimerMinutes;
    private Strategy currentRedStrategy;
    private Strategy currentBlueStrategy;
    private PlayerSide currentPlayerSide;
    private PlayerSide currentEnemySide;

    private final Random rng = new Random();

    @Override
    public void start(Stage stage) {
        System.err.println("[App] start() called");

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                java.nio.file.Path logPath = java.nio.file.Paths.get(
                        System.getProperty("user.home"),
                        ".stratrealms",
                        "crash.log");
                java.nio.file.Files.createDirectories(logPath.getParent());

                try (PrintWriter pw = new PrintWriter(new FileWriter(logPath.toFile(), true))) {
                    pw.println("=== " + LocalDateTime.now().format(
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " ===");
                    pw.println("Thread: " + thread.getName());
                    pw.println("Exception: " + throwable);
                    throwable.printStackTrace(pw);
                    pw.println();
                }
            } catch (Exception ignored) {
            }
            throwable.printStackTrace();
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("[App] JVM shutdown hook fired");
            new Exception("[App] shutdown hook stack").printStackTrace(System.err);
        }));

        DatabaseManager.get();
        playerDao        = new PlayerDao();
        collectionDao    = new CollectionDao();
        achievementDao   = new AchievementDao();
        matchHistoryDao  = new MatchHistoryDao();

        player = playerDao.loadOrCreateDefault();
        CurrentPlayer.set(player);

        AudioManager.setMusicVolume(player.musicVolume);
        AudioManager.setSfxVolume(player.sfxVolume);
        AudioManager.setMusicMuted(player.musicMuted);
        AudioManager.setSfxMuted(player.sfxMuted);

        loadSelectedStrategiesFromPlayer();
        loadCustomFormationFromPlayer();

        playerCollection   = PlayerCollection.fromDatabase(player, collectionDao);
        playerAchievements = PlayerAchievements.fromDatabase(player, achievementDao);

        this.stage = stage;
        stage.setTitle("StratRealms");
        stage.setMinWidth(700);
        stage.setMinHeight(500);

        stage.getIcons().add(
                new Image(getClass().getResourceAsStream("/assets/icon_logo.png"))
        );

        scene = new Scene(new StackPane(), 900, 650);
        scene.getStylesheets().addAll(
                getClass().getResource("/fantasy-theme.css").toExternalForm(),
                getClass().getResource("/deck-theme.css").toExternalForm(),
                getClass().getResource("/strategy-theme.css").toExternalForm(),
                getClass().getResource("/battle-theme.css").toExternalForm(),
                getClass().getResource("/countdown-theme.css").toExternalForm(),
                getClass().getResource("/how-to-play-theme.css").toExternalForm(),
                getClass().getResource("/shop-theme.css").toExternalForm(),
                getClass().getResource("/achievements-theme.css").toExternalForm(),
                getClass().getResource("/matchmaking-theme.css").toExternalForm()
        );

        stage.setScene(scene);

        stage.setOnCloseRequest(event -> {
            if (!confirmExit()) {
                event.consume();
            }
        });

        stage.setOnHidden(e -> {
            System.err.println("[App] stage hidden — window closed");
            new Exception("[App] stage hidden stack").printStackTrace(System.err);
        });

        countdownModal = new CountdownModal();

        AudioManager.playMusic(AudioManager.Music.MENU);

        showStartMenu();
        stage.show();
    }

    @Override
    public void stop() {
        System.err.println("[App] stop() called — JVM is shutting down");
        new Exception("[App] stop() stack trace").printStackTrace(System.err);

        AudioManager.shutdown();

        if (toastManager != null) toastManager.clearAll();
        if (matchmakingScreen != null) {
            try { matchmakingScreen.shutdown(); } catch (Exception ignored) {}
        }
        if (pendingTransport != null) {
            try { pendingTransport.close(); } catch (Exception ignored) {}
        }
        pendingLanConfig = null;
        DatabaseManager.get().close();
    }

    private void loadSelectedStrategiesFromPlayer() {
        if (player == null) return;

        if (player.strategyRed != null) {
            try { redStrategy = Strategy.valueOf(player.strategyRed); }
            catch (IllegalArgumentException ignored) {}
        }
        if (player.strategyBlue != null) {
            try { blueStrategy = Strategy.valueOf(player.strategyBlue); }
            catch (IllegalArgumentException ignored) {}
        }
    }

    private void loadCustomFormationFromPlayer() {
        if (player == null) return;

        try {
            FormationDao dao = new FormationDao();
            String json = dao.loadCustomFormationJson(player.id);
            if (json == null || json.isBlank()) {
                System.err.println("[App] No custom formation in DB.");
                return;
            }

            PieceType[][] saved = FormationJsonUtils.fromJson(json);
            if (saved == null) return;

            PieceType[][] target = Strategy.CUSTOM_STRATEGY.getFormationGrid();

            if (saved.length != target.length) return;
            for (int r = 0; r < target.length; r++) {
                if (saved[r] == null || saved[r].length != target[r].length) return;
                for (int c = 0; c < target[r].length; c++) {
                    if (saved[r][c] == null) return;
                }
            }

            for (int r = 0; r < target.length; r++) {
                System.arraycopy(saved[r], 0, target[r], 0, target[r].length);
            }

            System.err.println("[App] Custom formation pre-loaded from DB.");
        } catch (Exception e) {
            System.err.println("[App] Failed to pre-load custom formation: " + e.getMessage());
        }
    }

    private boolean hasBothStrategies() {
        return redStrategy != null && blueStrategy != null;
    }

    private String currentEquippedBoardTheme() {
        if (playerCollection == null) return null;
        CollectionItem equipped = playerCollection.getEquipped(CollectionCategory.BOARDS);
        return equipped == null ? null : equipped.themeStyleClass();
    }

    private CollectionItem currentEquippedSkin() {
        if (playerCollection == null) return null;
        return playerCollection.getEquipped(CollectionCategory.SKINS);
    }

    private void ensureToastManager() {
        if (gameLayout == null) return;

        if (toastManager == null) {
            toastManager = new ToastManager(gameLayout.getOverlayLayer());
            gameLayout.getOverlayLayer().getChildren().add(toastManager.getOverlayLayer());
        }
    }

    private void refreshAchievementBadge() {
        if (player == null || gameLayout == null) return;

        int unseen;
        try {
            unseen = achievementDao.unseenCount(player.id);
        } catch (Exception e) {
            unseen = 0;
        }
        gameLayout.getSidebar().setBadgeCount(unseen);
    }

    private void fireUnlockToasts(Set<String> newlyUnlockedIds) {
        if (newlyUnlockedIds == null || newlyUnlockedIds.isEmpty()) return;

        ensureToastManager();
        if (toastManager == null) return;

        for (String id : newlyUnlockedIds) {
            Achievement a = findAchievement(id);
            if (a == null) continue;

            toastManager.show(
                    a.displayName(),
                    a.description(),
                    v -> navigateTo(NavItem.ACHIEVEMENTS)
            );
        }
    }

    private Achievement findAchievement(String id) {
        if (playerAchievements == null) return null;
        for (AchievementCategory cat : AchievementCategory.values()) {
            for (Achievement a : playerAchievements.getByCategory(cat)) {
                if (a.id().equals(id)) return a;
            }
        }
        return null;
    }

    private Set<String> recordBattleResult(AchievementTracker tracker,
                                           Race race,
                                           boolean won,
                                           BattleResultModal.VictoryType victoryType,
                                           int timerMinutes,
                                           boolean wasBluetooth) {
        if (player == null || tracker == null) return Set.of();

        String victoryTypeName = victoryType == null ? "UNKNOWN" : victoryType.name();
        try {
            matchHistoryDao.record(
                    player.id,
                    race == null ? "UNKNOWN" : race.name(),
                    currentEnemyRace == null ? "UNKNOWN" : currentEnemyRace.name(),
                    playerSide == null ? "RED" : playerSide.name(),
                    timerMinutes,
                    won,
                    victoryTypeName);
        } catch (Exception e) {
            System.err.println("Failed to record match history: " + e.getMessage());
        }

        int offlineWins   = 0;
        int bluetoothWins = 0;
        Set<Race> racesWon = Set.of();
        try {
            offlineWins   = matchHistoryDao.offlineWins(player.id);
            bluetoothWins = matchHistoryDao.bluetoothWins(player.id);
            racesWon      = matchHistoryDao.racesEverWon(player.id);
        } catch (Exception e) {
            System.err.println("Failed to load match stats for achievements: " + e.getMessage());
        }

        AchievementEvaluator.Context ctx = new AchievementEvaluator.Context(
                playerSide,
                race,
                won,
                wasBluetooth,
                timerMinutes,
                offlineWins,
                bluetoothWins,
                racesWon,
                playerAchievements.unlockedCount());

        Set<String> newlyUnlocked;
        try {
            newlyUnlocked = AchievementEvaluator.evaluate(
                    tracker,
                    currentBattleScreen == null ? null : currentBattleScreen.getEngine(),
                    playerAchievements,
                    ctx);
        } catch (Exception e) {
            e.printStackTrace();
            return Set.of();
        }

        for (String id : newlyUnlocked) {
            try {
                achievementDao.unlock(player.id, id);
            } catch (Exception e) {
                System.err.println("Failed to persist achievement " + id + ": " + e.getMessage());
            }
            playerAchievements.unlock(id);
        }

        if (!newlyUnlocked.isEmpty()) {
            fireUnlockToasts(newlyUnlocked);
            refreshAchievementBadge();
        }
        return newlyUnlocked;
    }

    private void showStartMenu() {
        if (matchmakingScreen != null) {
            try { matchmakingScreen.shutdown(); } catch (Exception ignored) {}
            matchmakingScreen = null;
        }
        if (pendingTransport != null) {
            try { pendingTransport.close(); } catch (Exception ignored) {}
            pendingTransport = null;
        }
        pendingLanConfig = null;

        AudioManager.playMusic(AudioManager.Music.MENU);

        StartMenuScreen startMenu = new StartMenuScreen(
                scene, stage,
                this::showRaceSelect,
                this::showLoadGameScreen
        );
        scene.setRoot(startMenu.getRoot());
    }

    private void showWifiMatch(int timerMinutes) {
        if (pendingTransport != null) {
            try { pendingTransport.close(); } catch (Exception ignored) {}
            pendingTransport = null;
        }
        pendingLanConfig = null;

        if (playerRace == null) {
            playerRace = resolvePlayerRaceFallback();
        }

        Strategy localStrategy = redStrategy != null ? redStrategy : Strategy.BLITZKRIEG;

        AudioManager.playMusic(AudioManager.Music.MENU);

        matchmakingScreen = new MatchmakingScreen(
                scene,
                stage,
                playerRace,
                localStrategy,
                timerMinutes,
                this::onPeerConnected,
                () -> navigateTo(NavItem.TAVERN)
        );
        scene.setRoot(matchmakingScreen.getRoot());
    }

    private void onPeerConnected(NetworkTransport transport,
                                 MatchmakingScreen.MatchConfig config) {
        this.pendingTransport = transport;
        this.pendingLanConfig = config;

        this.playerRace = config.localRace;
        this.enemyRace  = config.peerRace;
        this.playerSide = config.localSide;

        Strategy localStrategy = config.localStrategy != null
                ? config.localStrategy
                : Strategy.BLITZKRIEG;
        Strategy peerStrategy = config.peerStrategy != null
                ? config.peerStrategy
                : Strategy.BLITZKRIEG;

        if (config.localSide == PlayerSide.RED) {
            this.redStrategy  = localStrategy;
            this.blueStrategy = peerStrategy;
        } else {
            this.blueStrategy = localStrategy;
            this.redStrategy  = peerStrategy;
        }

        this.currentTimerMinutes = config.timerMinutes;

        onStartGame(config.localRace, config.timerMinutes);
    }

    private void showRaceSelect() {
        AudioManager.playMusic(AudioManager.Music.MENU);

        RaceSelectScreen raceSelect = new RaceSelectScreen(
                scene, playerCollection, this::onRaceConfirmed, this::showStartMenu);
        scene.setRoot(raceSelect.getRoot());
    }

    private void onRaceConfirmed(Race race) {
        this.playerRace = race;
        this.enemyRace = Race.random();

        player.selectedRace = race.name();
        playerDao.updateSelectedRace(player.id, race.name());

        gameLayout = new GameLayout(scene, this::onNavigate);
        scene.setRoot(gameLayout.getRoot());

        ensureToastManager();
        refreshAchievementBadge();

        navigateTo(NavItem.TAVERN);
    }

    private void onNavigate(NavItem item) {
        if (item == NavItem.EXIT) {
            if (confirmExit()) {
                stage.close();
            }
            return;
        }

        if (item == NavItem.OPTIONS) {
            AudioManager.playSfx(AudioManager.Sfx.NAVIGATE);
            if (gameLayout == null) {
                gameLayout = new GameLayout(scene, this::onNavigate);
                ensureToastManager();
            }
            if (scene.getRoot() != gameLayout.getRoot()) {
                scene.setRoot(gameLayout.getRoot());
            }
            VolumeOptionsModal.show(gameLayout.getContentArea());
            return;
        }

        AudioManager.playSfx(AudioManager.Sfx.NAVIGATE);
        navigateTo(item);
    }

    private void navigateTo(NavItem item) {
        if (currentBattleScreen != null) {
            currentBattleScreen.cleanup();
            currentBattleScreen = null;
        }

        if (gameLayout == null) {
            gameLayout = new GameLayout(scene, this::onNavigate);
            ensureToastManager();
        }

        if (playerRace == null) {
            playerRace = resolvePlayerRaceFallback();
        }
        if (enemyRace == null) {
            enemyRace = Race.random();
        }

        if (scene.getRoot() != gameLayout.getRoot()) {
            scene.setRoot(gameLayout.getRoot());
        }

        AudioManager.playMusic(AudioManager.Music.MENU);

        switch (item) {
            case TAVERN -> {
                this.enemyRace = Race.random();
                gameLayout.setContent(
                        new TavernScreen(
                                gameLayout.getContentArea(),
                                playerRace,
                                enemyRace,
                                () -> redStrategy,
                                () -> blueStrategy,
                                (race, timerMinutes) -> onStartGame(race, timerMinutes),
                                this::hasBothStrategies,
                                () -> navigateTo(NavItem.STRATEGIES),
                                this::showRaceSelect,
                                this::showWifiMatch
                        ).getRoot()
                );
            }
            case DECKS -> {
                CollectionItem skin = currentEquippedSkin();
                gameLayout.setContent(
                        new DeckScreen(
                                gameLayout.getContentArea(),
                                playerRace,
                                skin
                        ).getRoot()
                );
            }
            case SHOP -> {
                ShopScreen shop = new ShopScreen(
                        gameLayout.getContentArea(),
                        player,
                        playerDao,
                        collectionDao
                );
                gameLayout.setContent(shop.getRoot());
            }
            case COLLECTIONS -> {
                CollectionsScreen screen =
                        new CollectionsScreen(gameLayout.getContentArea(), playerCollection,
                                player, playerDao);
                screen.setAlertOverlay(new AlertOverlay(gameLayout.getContentArea()));
                screen.setOverlayHost(gameLayout.getContentArea());
                gameLayout.setContent(screen.getRoot());
            }
            case ACHIEVEMENTS -> {
                AchievementsScreen screen = new AchievementsScreen(
                        gameLayout.getContentArea(),
                        playerAchievements,
                        player,
                        achievementDao,
                        collectionDao,
                        playerCollection
                );
                gameLayout.setContent(screen.getRoot());

                try {
                    achievementDao.markAllSeen(player.id);
                } catch (Exception e) {
                    System.err.println("Failed to mark achievements seen: " + e.getMessage());
                }
                gameLayout.getSidebar().setBadgeCount(0);
            }
            case LOAD_GAME -> {
                LoadGameScreen screen = new LoadGameScreen(
                        gameLayout.getContentArea(),
                        this::resumeSavedGame,
                        () -> navigateTo(NavItem.TAVERN)
                );
                gameLayout.setContent(screen.getRoot());
            }
            case HOW_TO_PLAY ->
                gameLayout.setContent(
                        new HowToPlayScreen(gameLayout.getContentArea(), playerRace).getRoot()
                );
            case STRATEGIES -> {
                StrategyScreen screen = new StrategyScreen(
                        gameLayout.getContentArea(),
                        PlayerSide.RED,
                        redStrategy,
                        blueStrategy,
                        this::onStrategiesSelected);
                gameLayout.setContent(screen.getRoot());
            }
            case ARENA ->
                gameLayout.setContent(
                        new PlaceholderScreen(gameLayout.getContentArea(), "Arena", "Coming soon! Battle mode is under development.").getRoot()
                );
            case OPTIONS -> { /* handled in onNavigate before navigateTo */ }
            case EXIT -> { }
        }
        gameLayout.highlight(item);
    }

    private Race resolvePlayerRaceFallback() {
        if (playerRace != null) return playerRace;

        if (player != null && player.selectedRace != null) {
            try {
                return Race.valueOf(player.selectedRace);
            } catch (IllegalArgumentException ignored) {
            }
        }

        Race r = Race.random();
        if (player != null) {
            player.selectedRace = r.name();
            playerDao.updateSelectedRace(player.id, r.name());
        }
        return r;
    }

    private void onStrategiesSelected(StrategyScreen.StrategyPair pair) {
        if (pair == null || pair.red == null || pair.blue == null) return;

        this.redStrategy  = pair.red;
        this.blueStrategy = pair.blue;

        player.strategyRed  = pair.red.name();
        player.strategyBlue = pair.blue.name();
        playerDao.updateSelectedStrategies(player.id, player.strategyRed, player.strategyBlue);

        System.out.println("Strategies selected & saved:");
        System.out.println("  RED : " + pair.red.getDisplayName());
        System.out.println("  BLUE: " + pair.blue.getDisplayName());
    }

    private void onStartGame(Race race, int timerMinutes) {
        System.err.println("[App] onStartGame called: " + race + " timer=" + timerMinutes);

        if (pendingTransport == null) {
            playerSide = PlayerSide.values()[rng.nextInt(PlayerSide.values().length)];
        }
        PlayerSide enemySide = playerSide.getOpposite();

        this.currentPlayerRace = race;
        this.currentEnemyRace = enemyRace;
        this.currentTimerMinutes = timerMinutes;
        this.currentRedStrategy = redStrategy;
        this.currentBlueStrategy = blueStrategy;
        this.currentPlayerSide = playerSide;
        this.currentEnemySide = enemySide;

        if (currentBattleScreen != null) {
            currentBattleScreen.cleanup();
            currentBattleScreen = null;
        }

        if (pendingTransport != null) {
            MatchmakingScreen.MatchConfig cfg = pendingLanConfig;

            currentBattleScreen = new BattleScreen(
                    scene,
                    cfg.localRace,
                    cfg.peerRace,
                    cfg.timerMinutes,
                    redStrategy,
                    blueStrategy,
                    cfg.localSide,
                    cfg.peerSide,
                    () -> navigateTo(NavItem.TAVERN),
                    this::restartBattle,
                    this::recordBattleResult,
                    currentEquippedBoardTheme(),
                    currentEquippedSkin(),
                    cfg.transport,
                    cfg.iAmHost,
                    cfg.peerStrategy
            );

            pendingTransport = null;
            pendingLanConfig = null;
        } else {
            currentBattleScreen = new BattleScreen(
                    scene,
                    race,
                    enemyRace,
                    timerMinutes,
                    redStrategy,
                    blueStrategy,
                    playerSide,
                    enemySide,
                    () -> navigateTo(NavItem.TAVERN),
                    this::restartBattle,
                    this::recordBattleResult,
                    currentEquippedBoardTheme(),
                    currentEquippedSkin()
            );
        }

        scene.setRoot(currentBattleScreen.getRoot());

        countdownModal.showCountdown(playerSide, enemyRace, () -> {
            if (currentBattleScreen != null) {
                currentBattleScreen.startTimer();
            }
        });
    }

    private void restartBattle() {
        System.err.println("[App] restartBattle called");
        if (currentBattleScreen != null) {
            currentBattleScreen.cleanup();
            currentBattleScreen = null;
        }

        this.currentEnemyRace = Race.random();

        currentBattleScreen = new BattleScreen(
                scene,
                currentPlayerRace,
                currentEnemyRace,
                currentTimerMinutes,
                currentRedStrategy,
                currentBlueStrategy,
                currentPlayerSide,
                currentEnemySide,
                () -> navigateTo(NavItem.TAVERN),
                this::restartBattle,
                this::recordBattleResult,
                currentEquippedBoardTheme(),
                currentEquippedSkin()
        );

        scene.setRoot(currentBattleScreen.getRoot());

        countdownModal.showCountdown(currentPlayerSide, currentEnemyRace, () -> {
            if (currentBattleScreen != null) {
                currentBattleScreen.startTimer();
            }
        });
    }

    private void showLoadGameScreen() {
        if (gameLayout == null) {
            gameLayout = new GameLayout(scene, this::onNavigate);
            ensureToastManager();
        }

        if (scene.getRoot() != gameLayout.getRoot()) {
            scene.setRoot(gameLayout.getRoot());
        }

        AudioManager.playMusic(AudioManager.Music.MENU);

        LoadGameScreen screen = new LoadGameScreen(
                gameLayout.getContentArea(),
                this::resumeSavedGame,
                () -> navigateTo(NavItem.TAVERN)
        );
        gameLayout.setContent(screen.getRoot());
        gameLayout.highlight(NavItem.LOAD_GAME);
    }

    private void resumeSavedGame(long saveId) {
        SavedGameDao dao = new SavedGameDao();
        SavedGameDao.SavedGame g = dao.loadById(player.id, saveId);

        if (g == null) {
            Alert a = new Alert(Alert.AlertType.INFORMATION);
            a.setTitle("Load Game");
            a.setHeaderText(null);
            a.setContentText("That save could not be found.");
            a.initOwner(stage);
            a.showAndWait();
            return;
        }

        try {
            Race savedPlayerRace = Race.valueOf(g.playerRace());
            Race savedEnemyRace  = Race.valueOf(g.enemyRace());
            PlayerSide savedSide = PlayerSide.valueOf(g.playerSide());
            PlayerSide savedEnemySide = savedSide.getOpposite();
            Strategy savedStrategy = Strategy.valueOf(g.playerStrategy());

            this.playerRace = savedPlayerRace;
            this.enemyRace  = savedEnemyRace;
            this.currentPlayerRace = savedPlayerRace;
            this.currentEnemyRace  = savedEnemyRace;
            this.currentTimerMinutes = g.timerMinutes();
            this.currentPlayerSide = savedSide;
            this.currentEnemySide  = savedEnemySide;

            if (savedSide == PlayerSide.RED) {
                this.currentRedStrategy  = savedStrategy;
                this.currentBlueStrategy = blueStrategy != null ? blueStrategy : Strategy.BLITZKRIEG;
            } else {
                this.currentBlueStrategy = savedStrategy;
                this.currentRedStrategy  = redStrategy  != null ? redStrategy  : Strategy.BLITZKRIEG;
            }

            player.selectedRace = savedPlayerRace.name();
            playerDao.updateSelectedRace(player.id, savedPlayerRace.name());

            if (gameLayout == null) {
                gameLayout = new GameLayout(scene, this::onNavigate);
                ensureToastManager();
            }

            if (currentBattleScreen != null) {
                currentBattleScreen.cleanup();
                currentBattleScreen = null;
            }

            currentBattleScreen = new BattleScreen(
                    scene,
                    savedPlayerRace,
                    savedEnemyRace,
                    g.timerMinutes(),
                    currentRedStrategy,
                    currentBlueStrategy,
                    savedSide,
                    savedEnemySide,
                    () -> navigateTo(NavItem.TAVERN),
                    this::restartBattle,
                    this::recordBattleResult,
                    currentEquippedBoardTheme(),
                    currentEquippedSkin()
            );

            scene.setRoot(currentBattleScreen.getRoot());

            GameStateSerializer.GameStateDTO dto =
                    GameStateSerializer.fromJson(g.boardJson());
            GameStateSerializer.applyToEngine(
                    currentBattleScreen.getEngine(), dto);

            currentBattleScreen.refreshBoardFromEngine();
            currentBattleScreen.restoreLostPiecesInSidebar();

            currentBattleScreen.resumeWithSeconds(g.remainingSeconds());

        } catch (Exception e) {
            e.printStackTrace();
            Alert a = new Alert(Alert.AlertType.ERROR);
            a.setTitle("Load Failed");
            a.setHeaderText("Could not load saved game");
            a.setContentText(e.getMessage() == null ? e.toString() : e.getMessage());
            a.initOwner(stage);
            a.showAndWait();
        }
    }

    private boolean confirmExit() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle("Exit Realm of Cards");
        alert.setHeaderText("Leave the tavern?");
        alert.setContentText("Are you sure you want to exit? Any unsaved progress will be lost.");
        alert.setGraphic(null);

        alert.getDialogPane().getStylesheets().add(getClass().getResource("/fantasy-theme.css").toExternalForm());
        alert.getDialogPane().getStyleClass().add("exit-dialog");

        ButtonType exitButton = new ButtonType("Exit", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(exitButton, cancelButton);

        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == exitButton;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
