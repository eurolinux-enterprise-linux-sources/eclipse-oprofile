/*******************************************************************************
 * Copyright (c) 2009 Red Hat, Inc. and others
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Kent Sebastian <ksebasti@redhat.com> - initial API and implementation
 *******************************************************************************/ 
package org.eclipse.linuxtools.oprofile.launch.launching;

import java.lang.reflect.InvocationTargetException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.ILaunchesListener2;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.linuxtools.oprofile.core.OpcontrolException;
import org.eclipse.linuxtools.oprofile.core.OprofileCorePlugin;
import org.eclipse.linuxtools.oprofile.core.daemon.OprofileDaemonEvent;
import org.eclipse.linuxtools.oprofile.launch.OprofileLaunchMessages;
import org.eclipse.linuxtools.oprofile.launch.configuration.LaunchOptions;
import org.eclipse.linuxtools.oprofile.ui.view.OprofileViewSaveDefaultSessionAction;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;

public class OprofileManualLaunchConfigurationDelegate extends AbstractOprofileLaunchConfigurationDelegate {
	@Override
	protected void preExec(LaunchOptions options, OprofileDaemonEvent[] daemonEvents) {
//		//set up the oprofile daemon
//		try {
//			//kill the daemon (it shouldn't be running already, but to be safe)
//			oprofileShutdown();
//			
//			//reset data from the (possibly) existing default session, 
//			// otherwise multiple runs will combine samples and results
//			// won't make much sense
//			oprofileReset();
//			
//			//setup the events and other parameters
//			oprofileSetupDaemon(options.getOprofileDaemonOptions(), daemonEvents);
//		} catch (OpcontrolException oe) {
//			OprofileCorePlugin.showErrorDialog("opcontrolProvider", oe); //$NON-NLS-1$
//			return;
//		}
	}

	@Override
	protected void postExec(LaunchOptions options, OprofileDaemonEvent[] daemonEvents, ILaunch launch, Process process) {
		final LaunchOptions fOptions = options;
		final OprofileDaemonEvent[] fDaemonEvents = daemonEvents;
		final ILaunch fLaunch = launch;
		Display.getDefault().syncExec(new Runnable() { 
			public void run() {
				//TODO: have a initialization dialog to do reset and setupDaemon?
				// using a progress dialog, can't abort the launch if there's an exception..
				try {
					oprofileReset();
					oprofileSetupDaemon(fOptions.getOprofileDaemonOptions(), fDaemonEvents);
				} catch (OpcontrolException oe) {
					OprofileCorePlugin.showErrorDialog("opcontrolProvider", oe); //$NON-NLS-1$
					return;		//dont open the dialog
				}
				
				//manual oprofile control dialog
				final OprofiledControlDialog dlg = new OprofiledControlDialog();
				ILaunchManager lmgr = DebugPlugin.getDefault().getLaunchManager();
				
				//possible for the launched process to have terminated before opening the dialog
				if (!fLaunch.isTerminated()) {
					dlg.setBlockOnOpen(false);
					dlg.open();
					lmgr.addLaunchListener(new LaunchTerminationDialogCloser(fLaunch, dlg));
				}
			} 
		});
	}
	
	//A class used to listen for the termination of the current launch, and 
	// run some functions when it is finished. 
	class LaunchTerminationDialogCloser implements ILaunchesListener2 {
		private ILaunch launch;
		private OprofiledControlDialog dialog;
		public LaunchTerminationDialogCloser(ILaunch il, OprofiledControlDialog dlg) {
			launch = il;
			dialog = dlg;
		}
		public void launchesTerminated(ILaunch[] launches) {
			for (ILaunch l : launches) {
				//kill the dialog when the launch is done
				if (l.equals(launch)) {
					//must be in the ui thread else thread access errors
					Display.getDefault().asyncExec(new Runnable() {
						public void run() {
							dialog.close();

							//progress dialog for ensuring the daemon is shut down
							IRunnableWithProgress refreshRunner = new IRunnableWithProgress() {
								public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
									monitor.beginTask(OprofileLaunchMessages.getString("oprofiledcontroldialog.post.stopdaemon"), 1); //$NON-NLS-1$
									try {
										oprofileShutdown();
									} catch (OpcontrolException e) {
//									e.printStackTrace();
									}
									monitor.worked(1);
									monitor.done();
								}
							};
							ProgressMonitorDialog dialog = new ProgressMonitorDialog(null);
							try {
								dialog.run(true, false, refreshRunner);
							} catch (InvocationTargetException e) {
								e.printStackTrace();
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}
					});
				}
			}
		}
		public void launchesAdded(ILaunch[] launches) { /* dont care */}
		public void launchesChanged(ILaunch[] launches) { /* dont care */ }
		public void launchesRemoved(ILaunch[] launches) { /* dont care */ }
	}	
	
	/**
	 * A custom dialog box to control the oprofile daemon.
	 */
	private class OprofiledControlDialog extends MessageDialog {
		private Button _startDaemonButton;
		private Button _stopDaemonButton;
		private Button _refreshViewButton;
		private Button _resetSessionButton;
		private Button _saveSessionButton;
		private List _feedbackList;
		
		public OprofiledControlDialog () {
			super(new Shell(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell()), OprofileLaunchMessages.getString("oprofiledcontroldialog.title"), null, null, MessageDialog.NONE, new String[] { IDialogConstants.OK_LABEL }, 0); //$NON-NLS-1$
		
			//override styles; makes the dialog non-modal
			setShellStyle(SWT.CLOSE | SWT.TITLE );
		}
		
		@Override
	    protected Control createCustomArea(Composite parent) {
			Composite area = new Composite(parent, 0);
			Layout layout = new GridLayout(5, true);
			GridData gd = new GridData();
			
			area.setLayout(layout);
			area.setLayoutData(gd);
			
			Button startDaemonButton = new Button(area, SWT.PUSH);
			startDaemonButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
			startDaemonButton.setText(OprofileLaunchMessages.getString("oprofiledcontroldialog.buttons.startdaemon")); //$NON-NLS-1$
			startDaemonButton.addSelectionListener(new SelectionListener() {
				public void widgetDefaultSelected(SelectionEvent e) {
				}
				public void widgetSelected(SelectionEvent e) {
					try {
						oprofileStartCollection();
						_startDaemonButton.setEnabled(false);
						_stopDaemonButton.setEnabled(true);
						_refreshViewButton.setEnabled(true);
						_resetSessionButton.setEnabled(true);
						_saveSessionButton.setEnabled(true);
					} catch (OpcontrolException oe) {
						//disable buttons, notify user of error
						disableAllButtons();
						OprofileCorePlugin.showErrorDialog("opcontrolProvider", oe); //$NON-NLS-1$
					}
					addToFeedbackList(OprofileLaunchMessages.getString("oprofiledcontroldialog.feedback.startdaemon")); //$NON-NLS-1$
				}});
			_startDaemonButton = startDaemonButton;
			
			Button stopDaemonButton = new Button(area, SWT.PUSH);
			stopDaemonButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
			stopDaemonButton.setText(OprofileLaunchMessages.getString("oprofiledcontroldialog.buttons.stopdaemon")); //$NON-NLS-1$
			stopDaemonButton.setEnabled(false);		//disabled at start
			stopDaemonButton.addSelectionListener(new SelectionListener() {
				public void widgetDefaultSelected(SelectionEvent e) {
				}
				public void widgetSelected(SelectionEvent e) {
					try {
						oprofileShutdown();
						_startDaemonButton.setEnabled(true);
						_stopDaemonButton.setEnabled(false);
					} catch (OpcontrolException oe) {
						//disable buttons, notify user of error
						disableAllButtons();
						OprofileCorePlugin.showErrorDialog("opcontrolProvider", oe); //$NON-NLS-1$
					}
					addToFeedbackList(OprofileLaunchMessages.getString("oprofiledcontroldialog.feedback.stopdaemon")); //$NON-NLS-1$
				}});
			_stopDaemonButton = stopDaemonButton;
			
			Button saveSessionButton = new Button(area, SWT.PUSH);
			saveSessionButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
			saveSessionButton.setText(OprofileLaunchMessages.getString("oprofiledcontroldialog.buttons.savesession")); //$NON-NLS-1$
			saveSessionButton.setEnabled(false);		//disabled at start
			saveSessionButton.addSelectionListener(new SelectionListener() {
				public void widgetDefaultSelected(SelectionEvent e) {
				}
				public void widgetSelected(SelectionEvent e) {
					addToFeedbackList(OprofileLaunchMessages.getString("oprofiledcontroldialog.feedback.save")); //$NON-NLS-1$
					OprofileViewSaveDefaultSessionAction hack = new OprofileViewSaveDefaultSessionAction();
					hack.run();
				}});
			_saveSessionButton = saveSessionButton;
			
			Button resetSessionButton = new Button(area, SWT.PUSH);
			resetSessionButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
			resetSessionButton.setText(OprofileLaunchMessages.getString("oprofiledcontroldialog.buttons.resetsession")); //$NON-NLS-1$
			resetSessionButton.setEnabled(false);		//disabled at start
			resetSessionButton.addSelectionListener(new SelectionListener() {
				public void widgetDefaultSelected(SelectionEvent e) {
				}
				public void widgetSelected(SelectionEvent e) {
					try {
						oprofileReset();
					} catch (OpcontrolException oe) {
						//disable buttons, notify user of error
						disableAllButtons();
						OprofileCorePlugin.showErrorDialog("opcontrolProvider", oe); //$NON-NLS-1$
					}
					refreshOprofileView();	//without refresh can lead to inconsistencies for save session
					addToFeedbackList(OprofileLaunchMessages.getString("oprofiledcontroldialog.feedback.reset")); //$NON-NLS-1$
				}});
			_resetSessionButton = resetSessionButton;
			
			Button refreshViewButton = new Button(area, SWT.PUSH);
			refreshViewButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
			refreshViewButton.setText(OprofileLaunchMessages.getString("oprofiledcontroldialog.buttons.refreshview")); //$NON-NLS-1$
			refreshViewButton.setEnabled(false);		//disabled at start
			refreshViewButton.addSelectionListener(new SelectionListener() {
				public void widgetDefaultSelected(SelectionEvent e) {
				}
				public void widgetSelected(SelectionEvent e) {
					addToFeedbackList(OprofileLaunchMessages.getString("oprofiledcontroldialog.feedback.dumpsamples")); //$NON-NLS-1$
					try {
						oprofileDumpSamples();
					} catch (OpcontrolException oe) {
						//no error in this case; the user might refresh when the daemon isnt running
					}
					refreshOprofileView();
					addToFeedbackList(OprofileLaunchMessages.getString("oprofiledcontroldialog.feedback.refreshed")); //$NON-NLS-1$
				}});
			_refreshViewButton = refreshViewButton;
			

			List feedback = new List(area, SWT.READ_ONLY | SWT.MULTI | SWT.BORDER | SWT.V_SCROLL);
			feedback.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 5, 1));
			feedback.add(OprofileLaunchMessages.getString("oprofiledcontroldialog.feedback.init")); //$NON-NLS-1$
			_feedbackList = feedback;
			
	        return area;
	    }
		
		//helper function
		private void disableAllButtons() {
			_startDaemonButton.setEnabled(false);
			_stopDaemonButton.setEnabled(false);
			_refreshViewButton.setEnabled(false);
			_resetSessionButton.setEnabled(false);
			_saveSessionButton.setEnabled(false);
		}
		
		//a little hack to get the list to auto scroll to the newly added item
		private void addToFeedbackList(String s) {
			_feedbackList.add(s,0);
			_feedbackList.setTopIndex(0);
		}
	}
}
