/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.com; Email: contact@knime.com
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ---------------------------------------------------------------------
 *
 * History
 *   Apr 26, 2018 (wiswedel): created
 */
package org.knime.core.internal;

import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.service.datalocation.Location;
import org.knime.core.node.KNIMEConstants;
import org.knime.core.node.NodeLogger;

/**
 * Utility class to check integrity of Eclipse Configuration area. For details see AP-9165; the configuration area
 * needs to be 'private' to the user as it otherwise may cause trouble in multi-user setups.
 *
 * @author Bernd Wiswedel
 * @since 3.6
 * @noreference This class is not intended to be referenced by clients.
 */
public final class ConfigurationAreaChecker {

    private static final String ERROR_APPENDIX = "This may lead to undesired effects in multi-user setups, see "
        + "KNIME FAQs: https://www.knime.com/faq#q34.";

    private ConfigurationAreaChecker() {
    }

    /**
     * @return the path of the eclipse config area as per {@link Platform#getConfigurationLocation()} or an empty
     *         optional if it can't be determined.
     */
    public static Optional<Path> getConfigurationLocationPath() {
        Location configLocation = Platform.getConfigurationLocation();
        if (configLocation != null) {
            URL configURL = configLocation.getURL();
            if (configURL != null) {
                String path = configURL.getPath();
                if (Platform.OS_WIN32.equals(Platform.getOS()) && path.matches("^/[a-zA-Z]:/.*")) {
                    // Windows path with drive letter => remove first slash
                    path = path.substring(1);
                }
                return Optional.of(Paths.get(path));
            }
        }
        return Optional.empty();
    }

    /**
     * Queues a scan of the config area of KNIME/Eclipse and checks if files can be locked by the current user, logging
     * an error if that's not possible.
     */
    public static void scheduleIntegrityCheck() {
        if (Boolean.getBoolean(KNIMEConstants.PROPERTY_DISABLE_VM_FILE_LOCK)) {
            getLogger().infoWithFormat("Disabled configuration area file locking due to system property \"%s\"",
                KNIMEConstants.PROPERTY_DISABLE_VM_FILE_LOCK);
        }
        Thread thread = new Thread(() -> {
            Path configLocationPath = getConfigurationLocationPath().orElse(null);
            try {
                Thread.sleep(60 * 1000); // some delay to make sure log messages occur in console if run in UI
                long start = System.currentTimeMillis();
                String currentUser = System.getProperty("user.name");
                if (configLocationPath == null) {
                    getLogger().warnWithFormat(
                        "Path to configuration area could not be determined (location URL is \"%s\")",
                        Platform.getConfigurationLocation().getURL());
                } else {
                    getLogger().debugWithFormat("Configuration area is at %s", configLocationPath);

                    Path knimeCoreConfigPath = configLocationPath.resolve("org.knime.core");
                    Files.createDirectories(knimeCoreConfigPath); // ignored when directory exists

                    Path userLockPath = knimeCoreConfigPath.resolve(currentUser + ".lock");

                    @SuppressWarnings("resource")
                    FileChannel userLockChannel = FileChannel.open(userLockPath, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

                    String fileContent = "File created on " + LocalDateTime.now().withNano(0).toString();
                    userLockChannel.write(StandardCharsets.UTF_8.encode(fileContent));
                    userLockChannel.force(true);

                    // lock the file and leave lock open until VM terminates
                    if (userLockChannel.tryLock() == null) {
                        getLogger().errorWithFormat(
                            "File in configuration area (\"%s\") cannot be locked by current user %s; either the "
                                + "file is already locked by a different KNIME instance or the file system does not "
                                + "support locking. %s",
                            userLockPath, currentUser, ERROR_APPENDIX);
                    } else {
                        getLogger().debugWithFormat("Locked file in configuration area \"%s\"", userLockPath);
                    }

                    // find other ".lock" files in the same directory and try to lock+release
                    Path[] otherLockPaths = Files.walk(knimeCoreConfigPath, 1)//
                            .filter(p -> p.getFileName().toString().endsWith(".lock"))//
                            .filter(p -> !p.equals(userLockPath))//
                            .toArray(Path[]::new);
                    for (Path p : otherLockPaths) {
                        try (FileChannel channel = FileChannel.open(p, StandardOpenOption.WRITE)) {
                            if (channel.tryLock() == null) {
                                getLogger().errorWithFormat(
                                    "Configuration area (\"%s\") seems to be used by a different process, "
                                        + "found file lock on \"%s\" (used by user %s). %s",
                                    configLocationPath, configLocationPath.relativize(p),
                                    StringUtils.removeEnd(p.getFileName().toString(), ".lock"), ERROR_APPENDIX);
                                break;
                            }
                        }
                    }

                }

                long end = System.currentTimeMillis();
                getLogger().debugWithFormat("Configuration area check completed in %.1fs", (end - start) / 1000.0);
            } catch (InterruptedException ie) {
                // ignore
            } catch (Exception ioe) {
                getLogger().error(String.format("Can't check integrity of configuration area (\"%s\"): %s",
                    configLocationPath, ioe.getMessage()), ioe);
            }
        }, "KNIME-ConfigurationArea-Checker");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Lazy getter of logger as code in this class might be called from static initializer in NodeLogger and/or
     * KNIMEConstants.
     *
     * @return logger instance
     */
    private static final NodeLogger getLogger() {
        return NodeLogger.getLogger(ConfigurationAreaChecker.class);
    }

}
