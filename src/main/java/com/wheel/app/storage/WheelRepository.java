package com.wheel.app.storage;

import com.wheel.app.format.WheelFormats;
import com.wheel.app.model.Models.WheelLibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WheelRepository {
    private final Path path;

    public WheelRepository(Path path) {
        this.path = path;
    }

    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), ".local", "share", "wwheel", "library.wwd.json");
    }

    public WheelLibrary load() {
        if (!Files.exists(path)) return new WheelLibrary();
        try {
            return WheelFormats.importWwd(Files.readAllBytes(path));
        } catch (Exception e) {
            e.printStackTrace();
            return new WheelLibrary();
        }
    }

    public void save(WheelLibrary library) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, WheelFormats.exportWwd(library, false));
    }
}
