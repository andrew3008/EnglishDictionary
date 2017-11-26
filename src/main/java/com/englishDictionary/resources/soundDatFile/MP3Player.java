package com.englishDictionary.resources.soundDatFile;

import javax.sound.sampled.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;

import static javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED;
import static javax.sound.sampled.AudioSystem.getAudioInputStream;

/**
 * Created by Andrew on 6/3/2017.
 *
 * Doc
 * http://www.onjava.com/2004/08/11/javasound-mp3.html
 *
 * https://odoepner.wordpress.com/2013/07/19/play-mp3-or-ogg-using-javax-sound-sampled-mp3spi-vorbisspi/
 * https://stackoverflow.com/questions/5667454/playing-mp3-using-java-sound-api
 */
public class MP3Player {

    private static int BUFFER_IO_LENGTH = 65536;
    private static byte[] bufferIO = new byte[BUFFER_IO_LENGTH];

    public static void play(InputStream is) throws IOException, URISyntaxException {
        try (final AudioInputStream din = getAudioInputStream(is)) {
            final AudioFormat outFormat = getOutFormat(din.getFormat());
            final DataLine.Info info = new DataLine.Info(SourceDataLine.class, outFormat);

            try (final SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
                if (line != null) {
                    line.open(outFormat);
                    line.start();
                    AudioInputStream inRes = getAudioInputStream(outFormat, din);
                    readStream(inRes, line);
                    line.drain();
                    line.stop();
                    line.close();
                    din.close();
                }
            }

        } catch (UnsupportedAudioFileException
                | LineUnavailableException
                | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AudioFormat getOutFormat(AudioFormat inFormat) {
        final int ch = inFormat.getChannels();
        final float rate = inFormat.getSampleRate();
        return new AudioFormat(PCM_SIGNED, rate, 16, ch, ch * 2, rate, false);
    }

    private static void readStream(AudioInputStream in, SourceDataLine line) throws IOException {
        for (int nrBytes = 0; nrBytes != -1; nrBytes = in.read(bufferIO, 0, BUFFER_IO_LENGTH)) {
            if (nrBytes == 0) {
                continue;
            }
            line.write(bufferIO, 0, nrBytes);
        }
    }

}
