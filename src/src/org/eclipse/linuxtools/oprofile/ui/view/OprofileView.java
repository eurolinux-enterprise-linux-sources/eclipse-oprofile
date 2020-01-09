/*******************************************************************************
 * Copyright (c) 2008, 2009 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Kent Sebastian <ksebasti@redhat.com> - initial API and implementation 
 *******************************************************************************/ 
package org.eclipse.linuxtools.oprofile.ui.view;

import java.lang.reflect.InvocationTargetException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.linuxtools.oprofile.core.model.OpModelRoot;
import org.eclipse.linuxtools.oprofile.ui.OprofileUiMessages;
import org.eclipse.linuxtools.oprofile.ui.OprofileUiPlugin;
import org.eclipse.linuxtools.oprofile.ui.model.UiModelRoot;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

/**
 * The view for the OProfile plugin. Shows the elements gathered by the data model
 *   in a tree viewer, parsed by the ui model (in the model package). The hierarchy
 *   (as it is displayed) looks like:
 *   
 *   UiModelRoot (not shown in the view)
 *   \_ UiModelEvent
 *   \_ ...
 *   \_ UiModelEvent
 *      \_ UiModelSession
 *      \_ ...
 *      \_ UiModelSession
 *         \_ UiModelImage
 *         |  \_ UiModelSymbol
 *         |  \_ ...
 *         |  \_ UiModelSymbol
 *         |     \_ UiModelSample
 *         |     \_ ...
 *         |     \_ UiModelSample
 *         \_ UiModelDependent
 *            \_ UiModelImage
 *            |  \_ ... (see above)
 *            \_ ...
 * 
 * The refreshView() function takes care of launching the data model parsing and
 *   ui model parsing in a separate thread.
 */
public class OprofileView extends ViewPart {
	private TreeViewer _viewer;

	@Override
	public void createPartControl(Composite parent) {
		_createTreeViewer(parent);
		_createActionMenu();

		OprofileUiPlugin.getDefault().setOprofileView(this);
	}
	
	private void _createTreeViewer(Composite parent) {
		_viewer = new TreeViewer(parent, SWT.SINGLE);
		_viewer.setContentProvider(new OprofileViewContentProvider());
		_viewer.setLabelProvider(new OprofileViewLabelProvider());
		_viewer.addDoubleClickListener(new OprofileViewDoubleClickListener());
	}

	private void _createActionMenu() {
		IMenuManager manager = getViewSite().getActionBars().getMenuManager();
		
		manager.add(new OprofileViewLogReaderAction());
		manager.add(new OprofileViewRefreshAction());
		manager.add(new OprofileViewSaveDefaultSessionAction());
	}
	
	private TreeViewer getTreeViewer() {
		return _viewer;
	}
	
	/**
	 * Extremely convoluted way of getting the running and parsing to happen in 
	 *   a separate thread, with a progress monitor. In most cases and on fast 
	 *   machines this will probably only be a blip.
	 */
	public void refreshView() {
		try {
			PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().showView(OprofileUiPlugin.ID_OPROFILE_VIEW);
		} catch (PartInitException e) {
			e.printStackTrace();
		}

		IRunnableWithProgress refreshRunner = new IRunnableWithProgress() {
			public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
				monitor.beginTask(OprofileUiMessages.getString("view.dialog.parsing.text"), 2); //$NON-NLS-1$

				OpModelRoot dataModelRoot = OpModelRoot.getDefault();
				dataModelRoot.refreshModel();
//				System.out.println(dataModelRoot);	//debugging
				monitor.worked(1);

				final UiModelRoot UiRoot = UiModelRoot.getDefault();
				UiRoot.refreshModel();
				
				Display.getDefault().asyncExec(new Runnable() {
					public void run() {
						OprofileUiPlugin.getDefault().getOprofileView().getTreeViewer().setInput(UiRoot);
					}
				});
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

	@Override
	public void setFocus() {
		// TODO Auto-generated method stub
	}

}
