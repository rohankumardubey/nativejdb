/*******************************************************************************
 * Copyright (c) 2006, 2013 Wind River Systems and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *     Ericsson			  - Additional handling of events
 *     Mikhail Khodjaiants (Mentor Graphics) - Refactor common code in GDBControl* classes (bug 372795)
 *
 *  Copyright (C) 2022 IBM Corporation
 *  This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License 2.0
 *  which accompanies this distribution, and is available at
 *  https://www.eclipse.org/legal/epl-2.0/
 *
 *  SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package gdb.mi.service.command;

import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import gdb.mi.service.command.output.MIOOBRecord;
import gdb.mi.service.command.output.MIOutput;
import gdb.mi.service.command.output.MIExecAsyncOutput;
import gdb.mi.service.command.output.MIResult;
import gdb.mi.service.command.output.MIValue;
import gdb.mi.service.command.output.MIConst;
import gdb.mi.service.command.output.MIStreamRecord;
import gdb.mi.service.command.events.MICatchpointHitEvent;
import gdb.mi.service.command.output.MIConsoleStreamOutput;
import gdb.mi.service.command.output.MIResultRecord;
import gdb.mi.service.command.events.MIEvent;
import gdb.mi.service.command.events.MIBreakpointHitEvent;
import gdb.mi.service.command.events.MIWatchpointTriggerEvent;
import gdb.mi.service.command.events.MIWatchpointScopeEvent;
import gdb.mi.service.command.events.MISteppingRangeEvent;
import gdb.mi.service.command.events.MISignalEvent;
import gdb.mi.service.command.events.MILocationReachedEvent;
import gdb.mi.service.command.events.MIFunctionFinishedEvent;
import gdb.mi.service.command.events.MISharedLibEvent;
import gdb.mi.service.command.events.MIInferiorExitEvent;
import gdb.mi.service.command.events.MIInferiorSignalExitEvent;
import gdb.mi.service.command.events.MIStoppedEvent;
import jdwp.ClassPrepareEvent;
import jdwp.GDBControl;
import jdwp.PacketStream;
import jdwp.Translator;

/**
 * MI debugger output listener that listens for the parsed MI output, and
 * generates corresponding MI events.  The generated MI events are then
 * received by other services and clients.
 */
public class MIRunControlEventProcessor implements Listener {
	private static final String STOPPED_REASON = "stopped"; //$NON-NLS-1$

	/**
	 * The connection service that this event processor is registered with.
	 */
	private final GDBControl fCommandControl;

	/**
	 * Container context used as the context for the run control events generated
	 * by this processor.
	 */

	/**
	 * Creates the event processor and registers it as listener with the debugger
	 * control.
	 * @param connection
	 * @since 1.1
	 */
	public MIRunControlEventProcessor(GDBControl connection) {
		fCommandControl = connection;
		connection.addEventListener(this);
	}

	/**
	 * This processor must be disposed before the control service is un-registered.
	 */

	public void dispose() {
		fCommandControl.removeEventListener(this);
	}

	public void onEvent(Object output) {
		if (output instanceof ClassPrepareEvent) {
			ClassPrepareEvent event = (ClassPrepareEvent) output;
			System.out.println("&&&&& " + event);
			PacketStream packetStream = Translator.translate(fCommandControl, event);
			if (packetStream != null) {
				packetStream.send();
			}
			return;
		}
		for (MIOOBRecord oobr : ((MIOutput) output).getMIOOBRecords()) {
			List<MIEvent> events = new LinkedList<>();
			if (oobr instanceof MIExecAsyncOutput) {
				MIExecAsyncOutput exec = (MIExecAsyncOutput) oobr;
				// Change of state.
				String state = exec.getAsyncClass();
				if ("stopped".equals(state)) { //$NON-NLS-1$
					// Re-set the thread and stack level to -1 when stopped event is recvd.
					// This is to synchronize the state between GDB back-end and AbstractMIControl.

					//fCommandControl.resetCurrentThreadLevel(); //MV -- TODO!!!!
					//fCommandControl.resetCurrentStackLevel();

					MIResult[] results = exec.getMIResults();
					for (int i = 0; i < results.length; i++) {
						String var = results[i].getVariable();
						MIValue val = results[i].getMIValue();
						if (var.equals("reason")) { //$NON-NLS-1$
							if (val instanceof MIConst) {
								String reason = ((MIConst) val).getString();
								MIEvent e = createEvent(reason, exec);
								if (e != null) {
									events.add(e);
									continue;
								}
							}
						}
					}

					// GDB < 7.0 does not provide a reason when stopping on a
					// catchpoint. However, the reason is contained in the
					// stream records that precede the exec async output one.
					// This is ugly, but we don't really have an alternative.
					if (events.isEmpty()) {
						MIStreamRecord[] streamRecords = ((MIOutput) output).getStreamRecords();
						for (MIStreamRecord streamRecord : streamRecords) {
							String log = streamRecord.getString();
							if (log.startsWith("Catchpoint ")) { //$NON-NLS-1$
								events.add(MICatchpointHitEvent.parse(exec.getToken(),
									results, streamRecord));
							}
						}
					}

					// We were stopped for some unknown reason, for example
					// GDB for temporary breakpoints will not send the
					// "reason" ??? still fire a stopped event.
					if (events.isEmpty()) {
						MIEvent e = createEvent(STOPPED_REASON, exec);
						if (e != null) {
							events.add(e);
						}
					}

					for (MIEvent event : events) {
						System.out.println("&&&&& " + event);
						PacketStream packetStream = Translator.translate(fCommandControl, event);
						if (packetStream != null) {
							packetStream.send();
						}
					}
				}
			} else if (oobr instanceof MIConsoleStreamOutput) {
				MIConsoleStreamOutput stream = (MIConsoleStreamOutput) oobr;
				if (stream.getCString().startsWith("Program terminated with signal")) {//$NON-NLS-1$

					/*
					 * The string should be in the form "Program terminated with signal <signal>, <reason>."
					 *  For Example:  Program terminated with signal SIGABRT, Aborted.
					 */

					// Parse the <signal> and the <reason>
					Pattern pattern = Pattern.compile("Program terminated with signal (.*), (.*)\\..*"); //$NON-NLS-1$
					Matcher matcher = pattern.matcher(stream.getCString());
					if (matcher.matches()) {
						MIExecAsyncOutput exec = new MIExecAsyncOutput();

						MIResult name = new MIResult();
						name.setVariable("signal-name"); //$NON-NLS-1$
						MIConst nameValue = new MIConst();
						nameValue.setCString(matcher.group(1));
						name.setMIValue(nameValue);

						MIResult meaning = new MIResult();
						meaning.setVariable("signal-meaning"); //$NON-NLS-1$
						MIConst meaningValue = new MIConst();
						meaningValue.setCString(matcher.group(2));
						meaning.setMIValue(meaningValue);

						exec.setMIResults(new MIResult[] { name, meaning });
						MIEvent event = createEvent("signal-received", exec); //$NON-NLS-1$
						// TODO MV
						System.out.println("******* " + event);
						//fCommandControl.getSession().dispatchEvent(event, fCommandControl.getProperties());
					}
				}
			}
		}


	}



	protected MIEvent createEvent(String reason, MIExecAsyncOutput exec) {
		MIEvent event = null;
		if ("breakpoint-hit".equals(reason)) { //$NON-NLS-1$
			event = MIBreakpointHitEvent.parse(exec.getToken(), exec.getMIResults());
		} else if ("watchpoint-trigger".equals(reason) //$NON-NLS-1$
			|| "read-watchpoint-trigger".equals(reason) //$NON-NLS-1$
			|| "access-watchpoint-trigger".equals(reason)) { //$NON-NLS-1$
			event = MIWatchpointTriggerEvent.parse(exec.getToken(), exec.getMIResults());
		} else if ("watchpoint-scope".equals(reason)) { //$NON-NLS-1$
			event = MIWatchpointScopeEvent.parse(exec.getToken(), exec.getMIResults());
		} else if ("end-stepping-range".equals(reason)) { //$NON-NLS-1$
			event = MISteppingRangeEvent.parse(exec.getToken(), exec.getMIResults());
		} else if ("signal-received".equals(reason)) { //$NON-NLS-1$
			event = MISignalEvent.parse(exec.getToken(), exec.getMIResults());
		} else if ("location-reached".equals(reason)) { //$NON-NLS-1$
			event = MILocationReachedEvent.parse(exec.getToken(), exec.getMIResults());
		} else if ("function-finished".equals(reason)) { //$NON-NLS-1$
			event = MIFunctionFinishedEvent.parse(exec.getToken(), exec.getMIResults());
		} else if ("solib-event".equals(reason)) { //$NON-NLS-1$
			event = MISharedLibEvent.parse(exec.getToken(), exec.getMIResults(), null);
		} else if ("exited-normally".equals(reason) || "exited".equals(reason)) { //$NON-NLS-1$ //$NON-NLS-2$
			event = MIInferiorExitEvent.parse(exec.getToken(), exec.getMIResults());
			// Until we clean up the handling of all these events, we need to send the containerExited event
			// Only needed GDB < 7.0, because GDB itself does not yet send an MI event about the inferior terminating
			//sendContainerExitedEvent();
		} else if ("exited-signalled".equals(reason)) { //$NON-NLS-1$
			event = MIInferiorSignalExitEvent.parse(exec.getToken(), exec.getMIResults());
			// Until we clean up the handling of all these events, we need to send the containerExited event
			// Only needed GDB < 7.0, because GDB itself does not yet send an MI event about the inferior terminating
			//sendContainerExitedEvent();
		} else if (STOPPED_REASON.equals(reason)) {
			event = MIStoppedEvent.parse(exec.getToken(), exec.getMIResults());
		}
		return event;
	}

	// TODO: MV -- See if anything in the code below is needed.
//	private void sendContainerExitedEvent() {
//		IMIProcesses procService = fServicesTracker.getService(IMIProcesses.class);
//		if (procService != null) {
//			IContainerDMContext processContainerDmc = procService.createContainerContextFromGroupId(fControlDmc,
//				MIProcesses.UNIQUE_GROUP_ID);
//
//			fCommandControl.getSession().dispatchEvent(new ContainerExitedDMEvent(processContainerDmc),
//				fCommandControl.getProperties());
//		}
//	}



//	@Override
//	public void commandDone(ICommandToken token, ICommandResult result) {
//		ICommand<?> cmd = token.getCommand();
//		MIInfo cmdResult = (MIInfo) result;
//		MIOutput output = cmdResult.getMIOutput();
//		MIResultRecord rr = output.getMIResultRecord();
//		if (rr != null) {
//			int id = rr.getToken();
//			// Check if the state changed.
//			String state = rr.getResultClass();
//			if ("running".equals(state)) { //$NON-NLS-1$
//				int type = 0;
//				// Check the type of command
//				// if it was a step instruction set state stepping
//
//				if (cmd instanceof MIExecNext) {
//					type = MIRunningEvent.NEXT;
//				} else if (cmd instanceof MIExecNextInstruction) {
//					type = MIRunningEvent.NEXTI;
//				} else if (cmd instanceof MIExecStep) {
//					type = MIRunningEvent.STEP;
//				} else if (cmd instanceof MIExecStepInstruction) {
//					type = MIRunningEvent.STEPI;
//				} else if (cmd instanceof MIExecUntil) {
//					type = MIRunningEvent.UNTIL;
//				} else if (cmd instanceof MIExecFinish) {
//					type = MIRunningEvent.FINISH;
//				} else if (cmd instanceof MIExecReturn) {
//					type = MIRunningEvent.RETURN;
//				} else if (cmd instanceof MIExecContinue) {
//					type = MIRunningEvent.CONTINUE;
//				} else {
//					type = MIRunningEvent.CONTINUE;
//				}
//
//				IMIProcesses procService = fServicesTracker.getService(IMIProcesses.class);
//				if (procService != null) {
//					IContainerDMContext processContainerDmc = procService.createContainerContextFromGroupId(fControlDmc,
//						MIProcesses.UNIQUE_GROUP_ID);
//
//					fCommandControl.getSession().dispatchEvent(new MIRunningEvent(processContainerDmc, id, type),
//						fCommandControl.getProperties());
//				}
//			} else if ("exit".equals(state)) { //$NON-NLS-1$
//				// No need to do anything, terminate() will.
//				// Send exited?
//			} else if ("connected".equals(state)) { //$NON-NLS-1$
//				// This will happen for a CORE or REMOTE session.
//				// For a CORE session this is the only indication
//				// that the inferior has 'started'.  So we use
//				// it to trigger the ContainerStarted event.
//				// In the case of a REMOTE session, it is a proper
//				// indicator as well but not if it is a remote attach.
//				// For an attach session, it only indicates
//				// that we are connected to a remote node but we still
//				// need to wait until we are attached to the process before
//				// sending the event, which will happen in the attaching code.
//				IGDBBackend backendService = fServicesTracker.getService(IGDBBackend.class);
//				if (backendService != null && backendService.getIsAttachSession() == false) {
//					IMIProcesses procService = fServicesTracker.getService(IMIProcesses.class);
//					if (procService != null) {
//						IContainerDMContext processContainerDmc = procService
//							.createContainerContextFromGroupId(fControlDmc, MIProcesses.UNIQUE_GROUP_ID);
//
//						fCommandControl.getSession().dispatchEvent(new ContainerStartedDMEvent(processContainerDmc),
//							fCommandControl.getProperties());
//					}
//				}
//			} else if ("error".equals(state)) { //$NON-NLS-1$
//			} else if ("done".equals(state)) { //$NON-NLS-1$
//				// For GDBs older than 7.0, GDB does not trigger a *stopped event
//				// when it stops due to a CLI command.  We have to trigger the
//				// MIStoppedEvent ourselves
//				if (cmd instanceof CLICommand<?>) {
//					// It is important to limit this to runControl operations (e.g., 'next', 'continue', 'jump')
//					// There are other CLI commands that we use that could still be sent when the target is considered
//					// running, due to timing issues.
//					boolean isAttachingOperation = CLIEventProcessor
//						.isAttachingOperation(((CLICommand<?>) cmd).getOperation());
//					boolean isSteppingOperation = CLIEventProcessor
//						.isSteppingOperation(((CLICommand<?>) cmd).getOperation());
//					if (isSteppingOperation || isAttachingOperation) {
//						IRunControl runControl = fServicesTracker.getService(IRunControl.class);
//						IMIProcesses procService = fServicesTracker.getService(IMIProcesses.class);
//						if (runControl != null && procService != null) {
//							// We don't know which thread stopped so we simply create a container event.
//							IContainerDMContext processContainerDmc = procService
//								.createContainerContextFromGroupId(fControlDmc, MIProcesses.UNIQUE_GROUP_ID);
//
//							// An attaching operation is debugging a new inferior and always stops it.
//							// We should not check that the container is suspended, because at startup, we are considered
//							// suspended, even though we can get a *stopped event.
//							if (isAttachingOperation || runControl.isSuspended(processContainerDmc) == false) {
//								MIEvent<?> event = MIStoppedEvent.parse(processContainerDmc, id, rr.getMIResults());
//								fCommandControl.getSession().dispatchEvent(event, fCommandControl.getProperties());
//							}
//						}
//					}
//				}
//			}
//		}
//	}
}

