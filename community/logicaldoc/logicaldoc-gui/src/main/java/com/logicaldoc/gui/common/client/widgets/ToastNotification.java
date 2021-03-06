package com.logicaldoc.gui.common.client.widgets;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.RepeatingCommand;
import com.logicaldoc.gui.common.client.log.EventPanel;
import com.logicaldoc.gui.common.client.util.Util;
import com.smartgwt.client.types.Alignment;
import com.smartgwt.client.types.Overflow;
import com.smartgwt.client.types.VerticalAlignment;
import com.smartgwt.client.widgets.Label;
import com.smartgwt.client.widgets.Window;

/**
 * This is the window that must be showed to the user during a long LogicalDOC
 * computation.
 * 
 * @author Matteo Caruso - Logical Objects
 * @since 6.0
 */
public class ToastNotification extends Window {

	public ToastNotification(String messageText) {
		setShowEdges(false);
		setShowHeader(false);
		setShowHeaderBackground(false);
		setShowHeaderIcon(false);
		setShowResizer(false);
		setShowFooter(false);
		setAlign(Alignment.CENTER);
		setMargin(0);
		setMembersMargin(3);
		setPadding(0);
		setBodyColor("white");
		setBackgroundColor("white");
		setBorder("1px solid DarkBlue");
		setOverflow(Overflow.HIDDEN);
		setAutoSize(true);
		centerInPage();
		setVertical(true);

		Label message = new Label(messageText);
		message.setWrap(false);
		message.setAlign(Alignment.CENTER);
		message.setIcon(Util.imageUrl("info.gif"));
		message.setIconSize(32);
		message.setStyleName("contactingserver");
		message.setLayoutAlign(Alignment.CENTER);
		message.setLayoutAlign(VerticalAlignment.TOP);
		message.setBackgroundColor("white");
		message.setShowEdges(false);
		message.setAutoFit(true);
		message.setHeight(50);
		message.setMargin(0);

		addItem(message);

		Scheduler.get().scheduleFixedDelay(new RepeatingCommand() {

			@Override
			public boolean execute() {
				ToastNotification.this.destroy();
				return false;
			}
		}, 2000);
	}

	/**
	 * Shows toast notification and also writes in the status bar.
	 */
	public static void showNotification(String message) {
		new ToastNotification(message).show();
		EventPanel.get().info(message, null);
	}
}