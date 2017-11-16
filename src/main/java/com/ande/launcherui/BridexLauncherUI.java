/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.ande.launcherui;

import fxlauncher.FXManifest;
import fxlauncher.LibraryFile;
import fxlauncher.UIProvider;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundPosition;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BackgroundSize;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;

/**
 *
 * @author COMPAQ
 */
public class BridexLauncherUI implements UIProvider{
    private ProgressBar progressBar;
	private Stage stage;
	private VBox root;
	private Label label;

	public void init(Stage stage) {
		this.stage = stage;
                this.stage.setWidth(620);
                this.stage.setHeight(350);
	}

	public Parent createLoader(FXManifest manifest) {
		stage.setTitle("Ande's Project");
                
                // Default Splash Screen image
                Image image = new Image(getClass().getResource("/images/splash_screen.png").toExternalForm());
                
                // ---- Load splash Screen Image from cacheDir
                Path cacheDir = manifest.resolveCacheDir(null);
                // default splash screen in cacheDir
                Path splashScreen = cacheDir.resolve("splash_screen.png"); 
                
                // get ALL File in cacheDir
                List<URL> libs = manifest.files.stream()
                .filter(LibraryFile::loadForCurrentPlatform)
                .map(it -> it.toURL(cacheDir))
                .collect(Collectors.toList());
                     
                // find the splash_screen image with all extension
                for(URL x:libs){
                    try {
                        File a = new File(x.toURI());
                        String splash = a.getName();
                        if(splash.contains("splash_screen")){ 
                            splashScreen = cacheDir.resolve(splash);
                            break;
                        }
                    } catch (URISyntaxException ex) {
                        Logger.getLogger(BridexLauncherUI.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
                
                // change image of splash screen if exist
                if(Files.exists(splashScreen)){
                    try {
                        URL splashScreenURL = splashScreen.toFile().toURI().toURL();                                        
                        image = new Image(splashScreenURL.openStream());
                    } catch (IOException ex) {
                        Logger.getLogger(BridexLauncherUI.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
                
                // new BackgroundSize(width, height, widthAsPercentage, heightAsPercentage, contain, cover)
                BackgroundSize backgroundSize = new BackgroundSize(620, 350, true, true, true, false);
                // new BackgroundImage(image, repeatX, repeatY, position, size)
                BackgroundImage backgroundImage = new BackgroundImage(image, BackgroundRepeat.REPEAT, BackgroundRepeat.NO_REPEAT, BackgroundPosition.CENTER, backgroundSize);
                // new Background(images...)
                Background background = new Background(backgroundImage);

                label = new Label("Loading, please wait...");
                label.setStyle(manifest.updateLabelStyle);
                label.setTextAlignment(TextAlignment.CENTER);
                
		root = new VBox(label); 
                root.setPadding(new Insets(200, 0, 0, 0));
                //root.setStyle(manifest.wrapperStyle);
                root.setAlignment(Pos.CENTER);
                root.setBackground(background);
                root.prefWidth(620);
                root.prefHeight(350);

		return root;
	}

	public Parent createUpdater(FXManifest manifest) {
		stage.setTitle("Updating...");

		progressBar = new ProgressBar();
                progressBar.setStyle(manifest.progressBarStyle);

		//root.getChildren().remove(label);
                label.setText("Waiting for Update...");
		root.getChildren().add(progressBar);

                /*
		Timeline tl = new Timeline(
			new KeyFrame(Duration.seconds(4), new KeyValue(header.scaleXProperty(), 1.5)),
			new KeyFrame(Duration.seconds(4), new KeyValue(header.scaleYProperty(), 1.5))
		);
		tl.play();*/

		return root;
	}

	public void updateProgress(double progress) {
		progressBar.setProgress(progress);
	}
        
        public void setLabelProgress(String text){
            label.setText(text);
        }

        @Override
        public ProgressBar getProgress() {
            return progressBar;
        }
}
