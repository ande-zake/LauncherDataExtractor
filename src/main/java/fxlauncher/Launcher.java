package fxlauncher;

import com.sun.javafx.application.ParametersImpl;
import com.sun.javafx.application.PlatformImpl;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import javax.xml.bind.JAXB;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@SuppressWarnings("unchecked")
public class Launcher extends Application {
    private static final Logger log = Logger.getLogger("Launcher");

    private FXManifest manifest;
    private Application app;
    private Stage primaryStage;
    private Stage stage;
    private String phase;
    private UIProvider uiProvider;
    private StackPane root;
    
    Double progress;

    /**
     * Initialize the UI Provider by looking for an UIProvider inside the launcher
     * or fallback to the default UI.
     * <p>
     * A custom implementation must be embedded inside the launcher jar, and
     * /META-INF/services/fxlauncher.UIProvider must point to the new implementation class.
     * <p>
     * You must do this manually/in your build right around the "embed manifest" step.
     */
    public void init() throws Exception {
        loadManifestFromApp();
        Iterator<UIProvider> providers = ServiceLoader.load(UIProvider.class).iterator();
        uiProvider = providers.hasNext() ? providers.next() : new DefaultUIProvider();
    }

    public void start(Stage primaryStage) throws Exception {
        this.primaryStage = primaryStage;
        stage = new Stage(StageStyle.UNDECORATED);
        root = new StackPane();
        Scene scene = new Scene(root);
        
        stage.setScene(scene);

        this.uiProvider.init(stage);
        root.getChildren().add(uiProvider.createLoader(manifest));

        stage.show();
        
        new Thread(() -> {
            Thread.currentThread().setName("FXLauncher-Thread");
            
            Path cacheDir = manifest.resolveCacheDir(getParameters().getNamed());
            try {
                if(! Files.exists(cacheDir) || manifest.isDirEmpty(cacheDir)){
                    updateManifest();
                    createUpdateWrapper();
                    log.info(String.format("Using cache dir %s", cacheDir));
                    syncFiles(cacheDir);
                }
            } catch (Exception ex) {
                log.log(Level.WARNING, String.format("Error during %s phase", phase), ex);
            }
            
            try {
                createApplication();
                launchAppFromManifest();
            } catch (Exception ex) {
                reportError(String.format("Error during %s phase", phase), ex);
            }

        }).start();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private void createUpdateWrapper() {
        phase = "Update Wrapper Creation";

        Platform.runLater(() -> {
            Parent updater = uiProvider.createUpdater(manifest);
            root.getChildren().clear();
            root.getChildren().add(updater);
        });
    }

    private URLClassLoader createClassLoader(Path cacheDir) {
        List<URL> libs = manifest.files.stream()
                .filter(LibraryFile::loadForCurrentPlatform)
                .map(it -> it.toURL(cacheDir))
                .collect(Collectors.toList());
                
        return new URLClassLoader(libs.toArray(new URL[libs.size()]));
    }

    private void launchAppFromManifest() throws Exception {
        phase = "Application Init";
        Platform.runLater(() -> {
                uiProvider.setLabelProgress("Waiting for Building App...");
        });
        app.init();
        phase = "Application Start";
        PlatformImpl.runAndWait(() -> {
            try {
                primaryStage.showingProperty().addListener(observable -> {
                    if (stage.isShowing()) stage.close();
                });
                app.start(primaryStage);
            } catch (Exception ex) {
                reportError("Failed to start application", ex);
            }
        });
    }

    private void updateManifest() throws Exception {
        phase = "Update Manifest";
        syncManifest();
    }

    private void syncFiles(Path cacheDir) throws Exception {
        phase = "File Synchronization";

        // berisi semua tag lib pada app.xml
        // @needsUpdate memfilter tag lib pada app.xml dengan kunci : 
        // jika, os(xmlAtribut) = OS.pada devicenya, karena jika OS nya ga sama berarti bukan update untuk OS tsb
        // dan 
        // needsUpdate dg kriteria, (file tidak ada) OR (size(xmlAtribut) = size.FilePadaDevice) OR (checksum(xmlAtribut)=checksum.FilePadaDevice)
        List<LibraryFile> needsUpdate = manifest.files.stream()
                .filter(LibraryFile::loadForCurrentPlatform)
                .filter(it -> it.needsUpdate(cacheDir))
                .collect(Collectors.toList());
        
        Long totalBytes = needsUpdate.stream().mapToLong(f -> f.size).sum();
        Long totalWritten = 0L;

        for (LibraryFile lib : needsUpdate) {
            Path target = cacheDir.resolve(lib.file).toAbsolutePath();
            Files.createDirectories(target.getParent());

            URI uri = manifest.uri.resolve(lib.file); // alamat file yang didownload
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            if (uri.getUserInfo() != null) {
                byte[] payload = uri.getUserInfo().getBytes(StandardCharsets.UTF_8);
                String encoded = Base64.getEncoder().encodeToString(payload);
                connection.setRequestProperty("Authorization", String.format("Basic %s", encoded));
            }
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(target)) {

                byte[] buf = new byte[65536];

                int read;
                while ((read = input.read(buf)) > -1) {
                    output.write(buf, 0, read);
                    totalWritten += read;
                    // dikali 0.8 karena 80% untuk update dan sisa nya untuk init
                    progress = totalWritten.doubleValue() / totalBytes.doubleValue() * 0.8;
                    Platform.runLater(() -> uiProvider.updateProgress(progress));
                }
            }
        }
    }

    private void createApplication() throws Exception {

        if (manifest == null) throw new IllegalArgumentException("Unable to retrieve embedded or remote manifest.");
        List<String> preloadLibs = manifest.getPreloadNativeLibraryList();
        for (String preloadLib : preloadLibs){
            System.loadLibrary(preloadLib);
        }

        Path cacheDir = manifest.resolveCacheDir(getParameters() != null ? getParameters().getNamed() : null);
        
        URLClassLoader classLoader = createClassLoader(cacheDir); // load class yang ada di chaceDir
        FXMLLoader.setDefaultClassLoader(classLoader);
        Thread.currentThread().setContextClassLoader(classLoader);
        Platform.runLater(() -> Thread.currentThread().setContextClassLoader(classLoader));
        Class<? extends Application> appclass = (Class<? extends Application>) classLoader.loadClass(manifest.launchClass);

        PlatformImpl.runAndWait(() -> {
            try {
                app = appclass.newInstance();
                ParametersImpl.registerParameters(app, new LauncherParams(getParameters(), manifest));
                PlatformImpl.setApplicationName(appclass);
            } catch (Throwable t) {
                reportError("Error creating app class", t);
            }
        });
    }

    public void stop() throws Exception {
        if (app != null)
            app.stop();
    }

    private void reportError(String title, Throwable error) {
        log.log(Level.WARNING, title, error);

        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(title);
            alert.getDialogPane().setPrefWidth(600);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintWriter writer = new PrintWriter(out);
            error.printStackTrace(writer);
            writer.close();
            TextArea text = new TextArea(out.toString());
            alert.getDialogPane().setContent(text);

            alert.showAndWait();
            Platform.exit();
        });
    }

    private void loadManifestFromApp(){
        try{
            // manifest based in launcher app
            URL embeddedManifest = Launcher.class.getResource("/app.xml");
            manifest = JAXB.unmarshal(embeddedManifest, FXManifest.class);
            
            Path cacheDir = manifest.resolveCacheDir(getParameters().getNamed());
            Path manifestPath = manifest.getPath(cacheDir);

            // manifest based in cacheDir
            if (Files.exists(manifestPath)){
                manifest = JAXB.unmarshal(manifestPath.toFile(), FXManifest.class);
            }
        }catch(Exception e){
            log.log(Level.WARNING, String.format("Unable load manifest from app !!!"), e);
        }
        
    }
    
    private void syncManifest() throws Exception {
        Map<String, String> namedParams = getParameters().getNamed(); 
        
        String appStr = null;

        if (namedParams.containsKey("app")) {
            // get --app-param
            appStr = namedParams.get("app");
            log.info(String.format("Loading manifest from 'app' parameter supplied: %s", appStr));
        }

        if (namedParams.containsKey("uri")) {
            // get --uri-param
            String uriStr  = namedParams.get("uri");
            if (! uriStr.endsWith("/")) { uriStr = uriStr + "/"; }
            log.info(String.format("Syncing files from 'uri' parameter supplied:  %s", uriStr));

            URI uri = URI.create(uriStr);
            // load manifest from --app param if supplied, else default file at supplied uri
            URI app = appStr != null ? URI.create(appStr) : uri.resolve("app.xml");
            manifest = FXManifest.load(app);
            // set supplied uri in manifest
            manifest.uri = uri;
            return;
        }

        if (appStr != null) {
            // --uri was not supplied, but --app was, so load manifest from that
            manifest = FXManifest.load(URI.create(appStr));
            return;
        }
                
        try {            
            Path cacheDir = manifest.resolveCacheDir(namedParams);
            Path manifestPath = manifest.getPath(cacheDir);
            
            System.out.println("manifest ts = "+manifest.ts);
            FXManifest remoteManifest = FXManifest.load(manifest.getFXAppURI());

            if (remoteManifest == null) {
                log.info(String.format("No remote manifest at %s", manifest.getFXAppURI()));
            } else if (!remoteManifest.equals(manifest)) {
                // Update to remote manifest if newer or we specifically accept downgrades
                if (remoteManifest.isNewerThan(manifest) || manifest.acceptDowngrade) {
                    System.out.println("manifest diperbaharui sesuai remote");
                    manifest = remoteManifest;
                    JAXB.marshal(manifest, manifestPath.toFile());
                }
            }
            System.out.println("remote ts = "+remoteManifest.ts);
        } catch (Exception ex) {
            log.log(Level.WARNING,
                    String.format("Unable to update manifest from %s", manifest.getFXAppURI()), ex);
        }
    }

}
