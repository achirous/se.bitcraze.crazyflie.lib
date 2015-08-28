package se.bitcraze.crazyflie.lib.bootloader;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.bitcraze.crazyflie.lib.bootloader.Target.TargetTypes;
import se.bitcraze.crazyflie.lib.bootloader.Utilities.BootVersion;
import se.bitcraze.crazyflie.lib.crazyradio.ConnectionData;
import se.bitcraze.crazyflie.lib.crtp.CrtpDriver;

/**
 * Bootloading utilities for the Crazyflie.
 *
 */
//TODO: fix targetId and addr confusion
//TODO: add flash method (for multiple targets)
public class Bootloader {

    final Logger mLogger = LoggerFactory.getLogger("Bootloader");

    private Cloader mCload;

    /**
     * Init the communication class by starting to communicate with the
     * link given. clink is the link address used after resetting to the
     * bootloader.
     *
     * The device is actually considered in firmware mode.
     */
    public Bootloader(CrtpDriver driver) {
        this.mCload = new Cloader(driver);
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
                /*
                self.protocol_version = self._cload.protocol_version
                if self.protocol_version == BootVersion.CF1_PROTO_VER_0 or\
                                self.protocol_version == BootVersion.CF1_PROTO_VER_1:
                    # Nothing more to do
                    pass
                elif self.protocol_version == BootVersion.CF2_PROTO_VER:
                    self._cload.request_info_update(TargetTypes.NRF51)
                else:
                    print "Bootloader protocol 0x{:X} not supported!".self.protocol_version
                */

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
        Target target = this.mCload.getTargets().get(0xFF);
        int configPage = target.getFlashPages() - 1;

        return this.mCload.readFlash(0xFF, configPage);
    }

    public void writeCF1Config(byte[] data) {
        Target target = this.mCload.getTargets().get(0xFF);
        int configPage = target.getFlashPages() - 1;

        /*
        to_flash = {"target": target, "data": data, "type": "CF1 config",
                "start_page": config_page}
        */

        //self._internal_flash(target=to_flash)
        internalFlash(target, data, "CF1 config", configPage);
    }

    // TODO: def flash(self, filename, targets):


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
        mLogger.debug("Bootloader close");
        if (this.mCload != null) {
            this.mCload.close();
        }
    }

    // def _internal_flash(self, target, current_file_number=1, total_files=1):
    public void internalFlash(Target target, byte[] data, String type, int configPage) {
        byte[] image = data;
        Target t_data = target;
        int startPage = configPage;
        int pageSize = t_data.getPageSize();

        mLogger.info("Flashing to " + TargetTypes.toString(t_data.getId()) + " (" + type + ")");
        System.out.println("Flashing to " + TargetTypes.toString(t_data.getId()) + " (" + type + ")");

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
            System.out.println(".");

            // Flash when the complete buffers are full
            if (bufferCounter >= t_data.getBufferPages()) {
                mLogger.info("BufferCounter: " + bufferCounter);
                if (!this.mCload.writeFlash(t_data.getId(), 0, startPage + i - (bufferCounter - 1), bufferCounter)) {
                    mLogger.error("Error during flash operation (code " + this.mCload.getErrorCode() + ". Maybe wrong radio link?");
                    //raise Exception()
                    return;

                }
                bufferCounter = 0;
            }
        }
        if (bufferCounter > 0) {
            mLogger.info("BufferCounter: " + bufferCounter);
            System.out.println("BufferCounter: " + bufferCounter);
            if (!this.mCload.writeFlash(t_data.getId(), 0, (startPage + ((image.length - 1) / pageSize)) - (bufferCounter - 1), bufferCounter)) {
                mLogger.error("Error during flash operation (code " + this.mCload.getErrorCode() + ". Maybe wrong radio link?");
                //raise Exception()
                return;
            }
        }
        mLogger.info("Flashing done!");
        System.out.println("Flashing done!");
    }

}
