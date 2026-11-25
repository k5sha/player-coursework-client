package ua.kpi.musicplayer;

import ua.kpi.musicplayer.audio.SimpleAudioPlayer;
import ua.kpi.musicplayer.model.Track;

import java.nio.file.Path;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        String location = args.length > 0 ? args[0] : readLocation(scanner);

        Track track = new Track(Path.of(location).getFileName().toString(), null, 0, location);
        SimpleAudioPlayer player = new SimpleAudioPlayer();
        player.play(track);

        scanner.nextLine();
        player.stop();
    }

    private static String readLocation(Scanner scanner) {
        System.out.print("Шлях до аудіофайлу: ");
        return scanner.nextLine().trim();
    }
}
