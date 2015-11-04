package se.bitcraze.crazyflie.lib.bootloader;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.bitcraze.crazyflie.lib.bootloader.Target.TargetTypes;
import se.bitcraze.crazyflie.lib.bootloader.Utilities.BootVersion;
import se.bitcraze.crazyflie.lib.crazyradio.ConnectionData;
import se.bitcraze.crazyflie.lib.crtp.CrtpDriver;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Bootloading utilities for the Crazyflie.
 *
 */
//TODO: fix targetId and addr confusion
//TODO: fix warmboot
public class Bootloader {

    final Logger mLogger = LoggerFactory.getLogger("Bootloader");

    private static ObjectMapper mMapper = new ObjectMapper(); // can reuse, share globally

    private Cloader mCload;

    private List<BootloaderListener> mBootloaderListeners;

    /**
     * Init the communication class by starting to communicate with the
     * link given. clink is the link address used after resetting to the
     * bootloader.
     *
     * The device is actually considered in firmware mode.
     */
    public Bootloader(CrtpDriver driver) {
        this.mCload = new Cloader(driver);
        this.mBootloaderListeners = Collections.synchronizedList(new LinkedList<BootloaderListener>());
    }

    public Cloader getCloader() {
        return this.mCload;
    }

    public boolean startBootloader(boolean warmboot) {
        boolean started = false;

        if (warmboot) {
            mLogger.info("startBootloader: warmboot");

            //TODO
        } else {
            mLogger.info("startBootloader: coldboot");
            ConnectionData bootloaderConnection = this.mCload.scanForBootloader();

            // Workaround for libusb on Windows (open/close too fast)
            //time.sleep(1)

            if (bootloaderConnection != null) {
                mLogger.info("startBootloader: bootloader connection found");
                this.mCload.openBootloaderConnection(bootloaderConnection);
                started = this.mCload.checkLinkAndGetInfo(TargetTypes.STM32); //TODO: what is the real parameter for this?
            } else {
                mLogger.info("startBootloader: bootloader connection NOT found");
                started = false;
            }

            if (started) {
                int protocolVersion = this.mCload.getProtocolVersion();
                if (protocolVersion == BootVersion.CF1_PROTO_VER_0 ||
                    protocolVersion == BootVersion.CF1_PROTO_VER_1) {
                    // Nothing to do
                } else if (protocolVersion == BootVersion.CF2_PROTO_VER) {
                    this.mCload.requestInfoUpdate(TargetTypes.NRF51);
                } else {
                    mLogger.debug("Bootloader protocol " + String.format("0x%02X", protocolVersion) + " not supported!");
                }

                mLogger.info("startBootloader: started");
            } else {
                mLogger.info("startBootloader: not started");
            }
        }
        return started;
    }

    public Target getTarget(int targetId) {
        return this.mCload.requestInfoUpdate(targetId);
    }

    public int getProtocolVersion() {
        return this.mCload.getProtocolVersion();
    }

    /**
     * Read a flash page from the specified target
     */
    public byte[] readCF1Config() {
        Target target = this.mCload.getTargets().get(TargetTypes.STM32); //CF1
        int configPage = target.getFlashPages() - 1;

        return this.mCload.readFlash(0xFF, configPage);
    }

    public void writeCF1Config(byte[] data) {
        Target target = this.mCload.getTargets().get(TargetTypes.STM32); //CF1
        int configPage = target.getFlashPages() - 1;

        //to_flash = {"target": target, "data": data, "type": "CF1 config", "start_page": config_page}
        FlashTarget toFlash = new FlashTarget(target, data, "CF1 config", configPage);
        internalFlash(toFlash);
    }

    //TODO: improve
    private byte[] readFile(File file) {
        byte[] fileData = new byte[(int) file.length()];
        mLogger.debug("File size: " +  file.length());
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file.getAbsoluteFile(), "r");
            raf.readFully(fileData);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return fileData;
    }

    public boolean flash(File file, String... targetNames) {
        List<FlashTarget> filesToFlash = getFlashTargets(file, targetNames);
        if (!file.exists()) {
            mLogger.error("File " + file + " does not exist.");
            return false;
        }
        if (filesToFlash.isEmpty()) {
            mLogger.error("Found no files to flash.");
            return false;
        }
        int fileCounter = 0;
        for (FlashTarget ft : filesToFlash) {
            internalFlash(ft, fileCounter, filesToFlash.size());
            fileCounter++;
        }
        return true;
    }

    //TODO: deal with different platforms (CF1, CF2)!?
    public List<FlashTarget> getFlashTargets(File file, String... targetNames) {
        List<FlashTarget> filesToFlash = new ArrayList<FlashTarget>();

        // check if supplied targetNames are known TargetTypes, if so, continue, else return

        if (isZipFile(file)) {
            // unzip
            unzip(file);

            // read manifest.json
            String manifestFilename = "manifest.json";
            File basePath = new File(file.getAbsoluteFile().getParent() + "/");
            File manifestFile = new File(basePath.getAbsolutePath() + "/" + manifestFilename);
            if (basePath.exists() && manifestFile.exists()) {
                Manifest mf = readManifest("manifest.json");
                Set<String> files = mf.getFiles().keySet();

                // iterate over file names in manifest.json
                for (String fileName : files) {
                    FirmwareDetails firmwareDetails = mf.getFiles().get(fileName);
                    Target t = this.mCload.getTargets().get(TargetTypes.fromString(firmwareDetails.getTarget()));
                    if (t != null) {
                        // use path to extracted file
                        //File flashFile = new File(file.getParent() + "/" + file.getName() + "/" + fileName);
                        File flashFile = new File(basePath.getAbsolutePath() + "/" + fileName);
                        FlashTarget ft = new FlashTarget(t, readFile(flashFile), firmwareDetails.getType(), t.getStartPage()); //TODO: does startPage HAVE to be an extra argument!? (it's already included in Target)
                        // add flash target
                        // if no target names are specified, flash everything
                        if (targetNames.length == 0 || targetNames[0].isEmpty()) {
                            filesToFlash.add(ft);
                        } else {
                            // else flash only files whose targets are contained in targetNames
                            if (Arrays.asList(targetNames).contains(firmwareDetails.getTarget())) {
                                filesToFlash.add(ft);
                            }
                        }
                    } else {
                        mLogger.error("No target found for " + firmwareDetails.getTarget());
                    }
                }
            } else {
                mLogger.error("Zip file " + file.getName() + " does not include a " + manifestFilename);
            }
        } else { // File is not a Zip file
            // add single flash target
            if (targetNames.length != 1) {
                mLogger.error("Not an archive, must supply ONE target to flash.");
            } else {

//                // assume stm32 if no target name is specified and file extension is ".bin"
//                if (targetNames[0].isEmpty() && file.getName().endsWith(".bin")) {
//                    targetNames = new String[] {"stm32"};
//                }

                for (String tn : targetNames) {
                    if (tn.isEmpty()) {
                        continue;
                    }
                    Target target = this.mCload.getTargets().get(TargetTypes.fromString(tn));
                    FlashTarget ft = new FlashTarget(target, readFile(file), "binary", target.getStartPage());
                    filesToFlash.add(ft);
                }
            }
        }
        return filesToFlash;
    }

    public void unzip(File zipFile) {
        mLogger.debug("Trying to unzip file " + zipFile + "...");
        InputStream fis = null;
        ZipInputStream zis = null;
        FileOutputStream fos = null;
        String parent = zipFile.getAbsoluteFile().getParent();

        try {
            fis = new FileInputStream(zipFile);
            zis = new ZipInputStream(new BufferedInputStream(fis));
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = zis.read(buffer)) != -1) {
                    baos.write(buffer, 0, count);
                }
                String filename = ze.getName();
                byte[] bytes = baos.toByteArray();
                // write files
                File filePath = new File(parent + "/" + filename);
                fos = new FileOutputStream(filePath);
                fos.write(bytes);
                //check
                if(filePath.exists() && filePath.length() > 0) {
                    mLogger.debug("File " + filename + " successfully unzipped.");
                } else {
                    mLogger.debug("Problems writing file " + filename + ".");
                }
            }
        } catch (FileNotFoundException ffe) {
            mLogger.error(ffe.getMessage());
        } catch (IOException ioe) {
            mLogger.error(ioe.getMessage());
        } finally {
            if (zis != null) {
                try {
                    zis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

        }
    }

    /**
     * Basic check if a file is a Zip file
     *
     * @param file
     * @return true if file is a Zip file, false otherwise
     */
    //TODO: how can this be improved?
    public boolean isZipFile(File file) {
        if (file != null && file.exists() && file.getName().endsWith(".zip")) {
            try {
                ZipFile zf = new ZipFile(file);
                return zf.size() > 0;
            } catch (ZipException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    /**
     * Reset to firmware depending on protocol version
     *
     * @return
     */
    public boolean resetToFirmware() {
        int targetType = -1;
        if (this.mCload.getProtocolVersion() == BootVersion.CF2_PROTO_VER) {
            targetType = TargetTypes.NRF51;
        } else {
            targetType = TargetTypes.STM32;
        }
        return this.mCload.resetToFirmware(targetType);
    }

    public void close() {
        mLogger.debug("Bootloader close()");
        if (this.mCload != null) {
            this.mCload.close();
        }
    }

    public void internalFlash(FlashTarget target) {
        internalFlash(target, 1, 1);
    }

    // def _internal_flash(self, target, current_file_number=1, total_files=1):
    public void internalFlash(FlashTarget flashTarget, int currentFileNo, int totalFiles) {
        Target t_data = flashTarget.getTarget();
        byte[] image = flashTarget.getData();
        int pageSize = t_data.getPageSize();
        int startPage = flashTarget.getStartPage();

        String flashingTo = "Flashing to " + TargetTypes.toString(t_data.getId()) + " (" + flashTarget.getType() + ")";
        mLogger.info(flashingTo);
        notifyUpdateStatus(flashingTo);

        //if len(image) > ((t_data.flash_pages - start_page) * t_data.page_size):
        if (image.length > ((t_data.getFlashPages() - startPage) * pageSize)) {
            mLogger.error("Error: Not enough space to flash the image file.");
            //raise Exception()
            return;
        }

        mLogger.info(image.length - 1 + " bytes (" + ((image.length / pageSize) + 1) + " pages) ");

        // For each page
        int bufferCounter = 0; // Buffer counter
        for (int i = 0; i < ((image.length - 1) / pageSize) + 1; i++) {
            // Load the buffer
            int end = 0;
            if (((i + 1) * pageSize) > image.length) {
                //buff = image[i * t_data.page_size:]
                end = image.length;
            } else {
                //buff = image[i * t_data.page_size:(i + 1) * t_data.page_size])
                end = (i + 1) * pageSize;
            }
            byte[] buffer = Arrays.copyOfRange(image, i * pageSize, end);
            this.mCload.uploadBuffer(t_data.getId(), bufferCounter, 0, buffer);

            bufferCounter++;

            // Flash when the complete buffers are full
            if (bufferCounter >= t_data.getBufferPages()) {
                String buffersFull = "Buffers full. Flashing page " + (i+1) + "...";
                mLogger.info(buffersFull);
                notifyUpdateStatus(buffersFull);
                notifyUpdateProgress(i+1);
                if (!this.mCload.writeFlash(t_data.getId(), 0, startPage + i - (bufferCounter - 1), bufferCounter)) {
                    handleFlashError();
                    //raise Exception()
                    return;

                }
                bufferCounter = 0;
            }
        }
        if (bufferCounter > 0) {
            mLogger.info("BufferCounter: " + bufferCounter);
            if (!this.mCload.writeFlash(t_data.getId(), 0, (startPage + ((image.length - 1) / pageSize)) - (bufferCounter - 1), bufferCounter)) {
                handleFlashError();
                //raise Exception()
                return;
            }
        }
        mLogger.info("Flashing done!");
        notifyUpdateStatus("Flashing done!");
    }

    private void handleFlashError() {
        String errorMessage = "Error during flash operation (" + this.mCload.getErrorMessage() + "). Maybe wrong radio link?";
        mLogger.error(errorMessage);
        notifyUpdateError(errorMessage);
    }


    public void addBootloaderListener(BootloaderListener bl) {
        this.mBootloaderListeners.add(bl);
    }

    public void removeBootloaderListener(BootloaderListener bl) {
        this.mBootloaderListeners.remove(bl);
    }

    public void notifyUpdateProgress(int progress) {
        for (BootloaderListener bootloaderListener : mBootloaderListeners) {
            bootloaderListener.updateProgress(progress);
        }
    }

    public void notifyUpdateStatus(String status) {
        for (BootloaderListener bootloaderListener : mBootloaderListeners) {
            bootloaderListener.updateStatus(status);
        }
    }

    public void notifyUpdateError(String error) {
        for (BootloaderListener bootloaderListener : mBootloaderListeners) {
            bootloaderListener.updateError(error);
        }
    }

    public interface BootloaderListener {

        public void updateProgress(int progress);

        public void updateStatus(String status);

        public void updateError(String error);

    }

    public class FlashTarget {

        private Target mTarget;
        private byte[] mData = new byte[0];
        private String mType = "";
        private int mStartPage;

        public FlashTarget(Target target, byte[] data, String type, int startPage) {
            this.mTarget = target;
            this.mData = data;
            this.mType = type;
            this.mStartPage = startPage;
        }

        public byte[] getData() {
            return mData;
        }

        public Target getTarget() {
            return mTarget;
        }

        public int getStartPage() {
            return mStartPage;
        }

        public String getType() {
            return mType;
        }

        @Override
        public String toString() {
            return "FlashTarget [target ID=" + TargetTypes.toString(mTarget.getId()) + ", data.length=" + mData.length + ", type=" + mType + ", startPage=" + mStartPage + "]";
        }

    }

    public static Manifest readManifest (String fileName) {
        String errorMessage = "";
        try {
            Manifest readValue = mMapper.readValue(new File(fileName), Manifest.class);
            return readValue;
        } catch (JsonParseException jpe) {
            errorMessage = jpe.getMessage();
        } catch (JsonMappingException jme) {
            errorMessage = jme.getMessage();
        } catch (IOException ioe) {
            errorMessage = ioe.getMessage();
        }
//        mLogger.error("Error while parsing manifest file " + fileName + ": " + errorMessage);
        return null;
    }

    public static void writeManifest (String fileName, Manifest manifest) {
        String errorMessage = "";
        mMapper.enable(SerializationFeature.INDENT_OUTPUT);
        try {
            mMapper.writeValue(new File(fileName), manifest);
        } catch (JsonGenerationException jge) {
            errorMessage = jge.getMessage();
        } catch (JsonMappingException jme) {
            errorMessage = jme.getMessage();
        } catch (IOException ioe) {
            errorMessage = ioe.getMessage();
        }
//        mLogger.error("Could not save manifest to file " + fileName + ".\n" + errorMessage);
    }
}
