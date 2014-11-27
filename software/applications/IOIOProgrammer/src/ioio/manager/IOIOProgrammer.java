/*
 * Copyright 2011 Ytai Ben-Tsvi. All rights reserved.
 * Copyright 2014 Wojciech Bober. All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 * 
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 * 
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL ARSHAN POURSOHI OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 * The views and conclusions contained in the software and documentation are those of the
 * authors and should not be interpreted as representing official policies, either expressed
 * or implied.
 */
package ioio.manager;

import ioio.lib.api.IcspMaster;
import ioio.lib.api.exception.ConnectionLostException;
import ioio.lib.util.BaseIOIOLooper;
import ioio.lib.util.IOIOLooper;
import ioio.lib.util.pc.IOIOConsoleApp;
import ioio.manager.IOIOFileProgrammer.ProgressListener;
import ioio.manager.IOIOFileReader.FormatException;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.logging.Logger;


public class IOIOProgrammer extends IOIOConsoleApp {
    enum ProgrammerState {
		STATE_IOIO_DISCONNECTED, STATE_IOIO_CONNECTED, STATE_TARGET_CONNECTED, STATE_UNKOWN_TARGET_CONNECTED, STATE_ERASE_START, STATE_PROGRAM_START, STATE_PROGRAM_IN_PROGRESS, STATE_ERASE_IN_PROGRESS, STATE_VERIFY_IN_PROGRESS, STATE_IOIO_INCOMPATIBLE
	}

	enum Chip {
		UNKNOWN(0x0000), PIC24FJ128DA106(0x4109), PIC24FJ128DA206(0x4108), PIC24FJ256DA106(
				0x410D), PIC24FJ256DA206(0x410C), PIC24FJ256GB206(0x4104);

		public int id;

		public static Chip findById(int id) {
			for (Chip res : values()) {
				if (res.id == id) {
					return res;
				}
			}
			return UNKNOWN;
		}

		private Chip(int id) {
			this.id = id;
		}
	}

	enum Board {
		UNKNOWN(Chip.UNKNOWN), SPRK0010(Chip.PIC24FJ128DA106), SPRK0011(
				Chip.PIC24FJ128DA106), SPRK0012(Chip.PIC24FJ128DA106), SPRK0013(
				Chip.PIC24FJ128DA206), SPRK0014(Chip.PIC24FJ128DA206), SPRK0015(
				Chip.PIC24FJ128DA206), SPRK0016(Chip.PIC24FJ256DA206), MINT0010(
				Chip.PIC24FJ256DA206), SPRK0020(Chip.PIC24FJ256GB206);

		public Chip chip;

		private Board(Chip chip) {
			this.chip = chip;
		}

		public static Board findByName(String name) {
			try {
				return valueOf(name);
			} catch (IllegalArgumentException e) {
				return UNKNOWN;
			}
		}
	}

    private final static Logger log_ = Logger.getLogger(IOIOProgrammer.class.getName());

	private File selectedImage_;
	private Chip target_;
	private boolean cancel_ = false;
    boolean abort = false;

	private ProgrammerState programmerState_;
	private ProgrammerState desiredState_;

    public static void main(String[] args) throws Exception {
        new IOIOProgrammer().go(args);
    }

    @Override
    protected void run(String[] args) throws Exception {
        if (args.length > 0) {
            selectedImage_ = new File(args[0]);
            desiredState_ = ProgrammerState.STATE_PROGRAM_START;
        }
        setProgrammerState(ProgrammerState.STATE_IOIO_DISCONNECTED);
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        String line;
        while (!abort && (line = reader.readLine()) != null) {
            if (line.equals("q")) {
                abort = true;
            }
        }
    }

    @Override
    public IOIOLooper createIOIOLooper(String connectionType, Object extra) {
        return new MainIOIOThread();
    }

	private synchronized void setProgrammerState(final ProgrammerState state) {
		programmerState_ = state;

        switch (state) {
        case STATE_IOIO_DISCONNECTED:
            log_.info("IOIO disconnected");
            break;

        case STATE_IOIO_CONNECTED:
            log_.info("IOIO connected");
            break;

        case STATE_IOIO_INCOMPATIBLE:
            log_.info("IOIO incompatible");
            break;

        case STATE_TARGET_CONNECTED:
            log_.info("Target " + target_ + " connected" );
            break;

        case STATE_UNKOWN_TARGET_CONNECTED:
            log_.info("Unknown target" );
            break;

        case STATE_PROGRAM_START:
        case STATE_ERASE_START:
            break;
        }
	}

	class MainIOIOThread extends BaseIOIOLooper implements ProgressListener {
		private int nTotalBlocks_;
		private int nBlocksDone_;
		private IcspMaster icsp_;

		@Override
		protected void setup() throws ConnectionLostException, InterruptedException {
			setProgrammerState(ProgrammerState.STATE_IOIO_CONNECTED);
			icsp_ = ioio_.openIcspMaster();
		}

		@Override
		public void loop() throws ConnectionLostException, InterruptedException {
			if (programmerState_ == ProgrammerState.STATE_TARGET_CONNECTED
					&& desiredState_ != ProgrammerState.STATE_IOIO_DISCONNECTED) {
				setProgrammerState(desiredState_);
				desiredState_ = ProgrammerState.STATE_IOIO_DISCONNECTED;
			}
			switch (programmerState_) {
			case STATE_IOIO_CONNECTED:
			case STATE_TARGET_CONNECTED:
			case STATE_UNKOWN_TARGET_CONNECTED:
				icsp_.enterProgramming();
				int targetId = Scripts.getDeviceId(ioio_, icsp_);
				if (targetId == 0xFFFF) {
					setProgrammerState(ProgrammerState.STATE_IOIO_CONNECTED);
				} else {
					target_ = Chip.findById(targetId);
					if (target_ == Chip.UNKNOWN) {
						setProgrammerState(ProgrammerState.STATE_UNKOWN_TARGET_CONNECTED);
					} else {
						setProgrammerState(ProgrammerState.STATE_TARGET_CONNECTED);
					}
				}
				Thread.sleep(100);
				icsp_.exitProgramming();
				break;

			case STATE_ERASE_START:
				try {
					icsp_.enterProgramming();
					setProgrammerState(ProgrammerState.STATE_ERASE_IN_PROGRESS);
					Scripts.chipErase(ioio_, icsp_);
					log_.info("Erased successfully");
				} catch (ConnectionLostException e) {
					log_.severe("Erase failed");
					throw e;
				} catch (InterruptedException e) {
                    log_.severe("Erase failed");
					throw e;
				} catch (Exception e) {
                    log_.severe("Erase failed");
				} finally {
					icsp_.exitProgramming();
					setProgrammerState(ProgrammerState.STATE_TARGET_CONNECTED);
				}
				break;

			case STATE_PROGRAM_START:
				try {
					cancel_ = false;
					icsp_.enterProgramming();
					setProgrammerState(ProgrammerState.STATE_ERASE_IN_PROGRESS);
                    Scripts.chipErase(ioio_, icsp_);
                    IOIOFileReader file = new IOIOFileReader(selectedImage_);
					nTotalBlocks_ = IOIOFileProgrammer.countBlocks(file);
                    nBlocksDone_ = 0;
                    log_.info("Using file " + selectedImage_.getAbsolutePath());
                    setProgrammerState(ProgrammerState.STATE_PROGRAM_IN_PROGRESS);
					file.rewind();
					while (file.next()) {
						if (cancel_) {
							log_.info("Aborted");
							return;
						}
						IOIOFileProgrammer.programIOIOFileBlock(ioio_, icsp_, file);
						blockDone();
					}
					setProgrammerState(ProgrammerState.STATE_VERIFY_IN_PROGRESS);
					nBlocksDone_ = 0;
					file.rewind();
					while (file.next()) {
						if (cancel_) {
							log_.info("Aborted");
							return;
						}
						if (!IOIOFileProgrammer.verifyIOIOFileBlock(ioio_, icsp_, file)) {
							log_.severe("File verification failed");
							return;
						}
						blockDone();
					}
					log_.info("Target programmed successfully");
				} catch (FormatException e) {
					log_.severe("Image corrupt");
				} catch (ConnectionLostException e) {
					log_.severe("Programming failed");
					throw e;
				} catch (InterruptedException e) {
                    log_.severe("Programming failed");
					throw e;
				} catch (Exception e) {
                    log_.severe("Programming failed");
				} finally {
					icsp_.exitProgramming();
                    abort = true;
					return;
				}
			}
			Thread.sleep(100);
		}

		@Override
		public void disconnected() {
            setProgrammerState(ProgrammerState.STATE_IOIO_DISCONNECTED);
		}
		
		@Override
		public void incompatible() {
            setProgrammerState(ProgrammerState.STATE_IOIO_INCOMPATIBLE);
		}

		@Override
		public void blockDone() {
            log_.info("Block " + ++nBlocksDone_ + " of " + nTotalBlocks_ + " done");
		}
	}
}