package space.br1440.platform.errorhandling.blockhound;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import reactor.blockhound.BlockHound;

public final class BlockHoundLauncherSessionListener implements LauncherSessionListener {

    public static final String DISABLED_PROPERTY = "blockhound.disabled";

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (Boolean.getBoolean(DISABLED_PROPERTY)) {
            return;
        }

        BlockHound.install();
    }
}
