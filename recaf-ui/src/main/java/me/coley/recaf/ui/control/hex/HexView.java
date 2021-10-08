package me.coley.recaf.ui.control.hex;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TabPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import me.coley.recaf.config.Configs;
import me.coley.recaf.ui.behavior.Cleanable;
import me.coley.recaf.ui.behavior.Representation;
import me.coley.recaf.ui.behavior.SaveResult;
import me.coley.recaf.ui.control.CollapsibleTabPane;
import me.coley.recaf.ui.dialog.TextInputDialog;
import me.coley.recaf.ui.util.Icons;
import me.coley.recaf.ui.util.Lang;
import me.coley.recaf.ui.util.Menus;
import org.fxmisc.flowless.VirtualFlow;
import org.fxmisc.flowless.Virtualized;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.reactfx.value.Val;
import org.reactfx.value.Var;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * A hex viewer and editor component that utilizes virtual/recyclable cells.
 * <br>
 * To populate the UI, see {@link #onUpdate(byte[])}.
 *
 * @author Matt Coley
 */
public class HexView extends BorderPane implements Cleanable, Representation, Virtualized {
	private final int hexColumns;
	private final HexAccessor hex = new HexAccessor(this);
	private final HexRange range = new HexRange(hex);
	private final HexStringsInfo strings = new HexStringsInfo(this);
	private final HexRow header;
	private final ObservableList<Integer> rowOffsets = FXCollections.observableArrayList();
	private final BorderPane hexDisplayWrapper = new BorderPane();
	private VirtualFlow<Integer, HexRow> hexFlow;
	private EditableHexLocation dragLocation;
	private ContextMenu menu;

	// TODO: Local control for:
	//  - toggle endian format
	//  - search for patterns (hex/values/string)

	// TODO: Syntax matching for known formats (just class to begin with, see HexClassView for details)

	// TODO: Search bar
	//  - Perhaps similar to workspace filter, highlighting matches

	/**
	 * Initialize the components.
	 */
	public HexView() {
		hexColumns = Configs.editor().hexColumns;
		header = new HexRow(this);
		header.updateItem(-1);
		Node headerNode = header.getNode();
		headerNode.getStyleClass().add("hex-header");
		setMinWidth(header.desiredTotalWidth());
		range.addListener(new SelectionHighlighter());
		setOnKeyPressed(e -> {
			KeyCode code = e.getCode();
			if (code == KeyCode.ESCAPE)
				range.clearSelection();
			else if (code == KeyCode.DELETE || code == KeyCode.BACK_SPACE)
				deleteSelection();
			else if (code == KeyCode.INSERT)
				insertAfterSelection();
			else if (code == KeyCode.C && e.isControlDown())
				copySelection();
		});
		setOnContextMenuRequested(e -> {
			// Close old menu
			if (menu != null) {
				menu.hide();
				menu = null;
			}
			// Create new menu and display it
			if (range.exists()) {
				menu = createMenu();
				menu.setAutoHide(true);
				menu.setHideOnEscape(true);
				menu.show(getScene().getWindow(), e.getScreenX(), e.getScreenY());
				menu.requestFocus();
			}
		});
		// Setup side tabs with extra utilities
		CollapsibleTabPane sideTabs = new CollapsibleTabPane();
		sideTabs.setSide(Side.RIGHT);
		sideTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
		sideTabs.getTabs().addAll(
				strings.createStringsTab()
		);
		sideTabs.setup();
		SplitPane split = new SplitPane();
		split.getItems().addAll(hexDisplayWrapper, sideTabs);
		split.setDividerPositions(0.75);
		setTop(headerNode);
		setCenter(split);
	}

	@Override
	public Val<Double> totalWidthEstimateProperty() {
		return hexFlow.totalWidthEstimateProperty();
	}

	@Override
	public Val<Double> totalHeightEstimateProperty() {
		return hexFlow.totalHeightEstimateProperty();
	}

	@Override
	public Var<Double> estimatedScrollXProperty() {
		return hexFlow.estimatedScrollXProperty();
	}

	@Override
	public Var<Double> estimatedScrollYProperty() {
		return hexFlow.estimatedScrollYProperty();
	}

	@Override
	public void scrollXBy(double deltaX) {
		hexFlow.scrollXBy(deltaX);
	}

	@Override
	public void scrollYBy(double deltaY) {
		hexFlow.scrollYBy(deltaY);
	}

	@Override
	public void scrollXToPixel(double pixel) {
		hexFlow.scrollXToPixel(pixel);
	}

	@Override
	public void scrollYToPixel(double pixel) {
		hexFlow.scrollYToPixel(pixel);
	}

	@Override
	public SaveResult save() {
		return SaveResult.IGNORED;
	}

	@Override
	public boolean supportsEditing() {
		return true;
	}

	@Override
	public Node getNodeRepresentation() {
		return this;
	}

	@Override
	public void cleanup() {
		if (hexFlow != null) {
			hexFlow.dispose();
		}
	}

	/**
	 * @return Context menu with actions based on the current selection.
	 */
	private ContextMenu createMenu() {
		ContextMenu menu = new ContextMenu();
		Menu menuCopy = Menus.menu("menu.hex.copyas");
		menuCopy.getItems().add(Menus.actionLiteral("String", Icons.QUOTE, () -> {
			int start = range.getStart();
			int end = range.getEnd();
			byte[] data = new byte[end - start];
			for (int i = start; i <= end; i++) {
				data[i - start] = (byte) hex.getHexAtOffset(i);
			}
			ClipboardContent clipboard = new ClipboardContent();
			clipboard.putString(new String(data));
			Clipboard.getSystemClipboard().setContent(clipboard);
		}));
		menuCopy.getItems().add(Menus.separator());
		menuCopy.getItems().add(Menus.actionLiteral("Java - short[]", Icons.CODE, () -> {
			List<String> bytes = new ArrayList<>();
			for (int i = range.getStart(); i <= getRange().getEnd(); i++) {
				bytes.add("0x" + hex.getHexStringAtOffset(i));
			}
			ClipboardContent clipboard = new ClipboardContent();
			clipboard.putString("short[] arr = { " + String.join(", ", bytes) + " };");
			Clipboard.getSystemClipboard().setContent(clipboard);
		}));
		menuCopy.getItems().add(Menus.actionLiteral("C - unsigned char[]", Icons.CODE, () -> {
			List<String> bytes = new ArrayList<>();
			for (int i = range.getStart(); i <= getRange().getEnd(); i++) {
				bytes.add("0x" + hex.getHexStringAtOffset(i));
			}
			ClipboardContent clipboard = new ClipboardContent();
			clipboard.putString("unsigned char arr[] = { " + String.join(", ", bytes) + " };");
			Clipboard.getSystemClipboard().setContent(clipboard);
		}));
		menuCopy.getItems().add(Menus.actionLiteral("Python - bytes", Icons.CODE, () -> {
			List<String> bytes = new ArrayList<>();
			for (int i = range.getStart(); i <= getRange().getEnd(); i++) {
				bytes.add("0x" + hex.getHexStringAtOffset(i));
			}
			ClipboardContent clipboard = new ClipboardContent();
			clipboard.putString("arr = bytes([ " + String.join(", ", bytes) + " ])");
			Clipboard.getSystemClipboard().setContent(clipboard);
		}));
		menu.getItems().add(menuCopy);
		return menu;
	}

	/**
	 * Copies the current selection to a hex string.
	 */
	private void copySelection() {
		if (range.exists()) {
			StringBuilder sb = new StringBuilder();
			if (dragLocation == EditableHexLocation.RAW) {
				// Copy the raw content as hex
				for (int i = range.getStart(); i <= range.getEnd(); i++) {
					sb.append(hex.getHexStringAtOffset(i));
				}
			} else {
				// Copy the displayed ascii
				int start = range.getStart();
				int end = range.getEnd();
				int length = end - start + 1;
				sb.append(hex.getPreviewAtOffset(start, length));
			}
			ClipboardContent clipboard = new ClipboardContent();
			clipboard.putString(sb.toString());
			Clipboard.getSystemClipboard().setContent(clipboard);
		}
	}

	/**
	 * Inserts a number of empty bytes after the current selection.
	 */
	private void insertAfterSelection() {
		if (!range.exists())
			return;
		String title = Lang.get("dialog.hex.title.insertcount");
		String header = Lang.get("dialog.hex.header.insertcount");
		TextInputDialog copyDialog = new TextInputDialog(title, header, Icons.getImageView(Icons.ACTION_EDIT));
		Optional<Boolean> result = copyDialog.showAndWait();
		if (result.isPresent() && result.get()) {
			// Parse input
			String text = copyDialog.getText();
			int count = -1;
			if (text.matches("\\d+")) {
				count = Integer.parseInt(text);
			} else if (text.matches("0x\\d+")) {
				count = Integer.parseInt(text, HexAccessor.HEX_RADIX);
			}
			// Insert and refresh UI
			if (count > 0) {
				int pos = range.getEnd();
				hex.insertEmptyAfter(pos, count);
				refreshPastOffset(pos);
			}
		}
	}

	/**
	 * Deletes the current selected range.
	 */
	private void deleteSelection() {
		// TODO: Have a "are you sure" prompt that can be disabled in config
		if (!range.exists())
			return;
		int start = range.getStart();
		int end = range.getEnd();
		// Clear
		range.clearSelection();
		// Delete
		hex.deleteRange(start, end);
		// Refresh UI
		refreshPastOffset(start);
	}

	/**
	 * Refreshes visible rows past the given offset.
	 *
	 * @param offset
	 * 		Some arbitrary offset.
	 */
	private void refreshPastOffset(int offset) {
		int incr = getHexColumns();
		int startOffset = offset - (offset % incr);
		int minIndex = startOffset / incr;
		int index = hexFlow.getLastVisibleIndex();
		while (index >= minIndex) {
			Optional<HexRow> rowAtIndex = hexFlow.getCellIfVisible(index);
			if (rowAtIndex.isPresent()) {
				HexRow row = rowAtIndex.get();
				row.updateItem(index * incr);
				index--;
			} else {
				break;
			}
		}
	}

	/**
	 * Populate the UI with new data.
	 *
	 * @param data
	 * 		Data to use.
	 */
	public void onUpdate(byte[] data) {
		hex.setBacking(data);
		List<Integer> newOffsets = hex.computeOffsetsInRange();
		rowOffsets.clear();
		rowOffsets.addAll(newOffsets);
		strings.populateStrings();
		if (hexFlow != null) {
			hexFlow.dispose();
		}
		hexFlow = VirtualFlow.createVertical(rowOffsets, i -> {
			HexRow row = new HexRow(this);
			row.updateItem(i);
			return row;
		});
		hexDisplayWrapper.setCenter(new VirtualizedScrollPane<>(hexFlow));
		// Requesting focus on click is how we force the scene to propagate key events to the hex-view.
		// We also only want to do it once as to not break mouse interactions with hex-rows/hex-labels.
		if (!hexDisplayWrapper.isFocused())
			hexDisplayWrapper.setOnMouseClicked(e -> {
				if (e.getClickCount() == 1)
					hexDisplayWrapper.requestFocus();
			});
	}

	/**
	 * Called when a {@link HexRow} is pressed.
	 *
	 * @param location
	 * 		Location where the drag originated from.
	 * @param offset
	 * 		Offset pressed on.
	 */
	public void onDragStart(EditableHexLocation location, int offset) {
		dragLocation = location;
		range.createSelectionBound(offset);
	}

	/**
	 * Called when a {@link HexRow} is released.
	 *
	 * @param offset
	 * 		Offset released on.
	 */
	public void onDragUpdate(int offset) {
		range.updateSelectionBound(offset);
	}

	/**
	 * Called when a {@link HexRow} is released.
	 * Unlike {@link #onDragStart(EditableHexLocation, int)} and {@link #onDragUpdate(int)} there is no parameter.
	 * The assumption is the last value from {@link #onDragUpdate(int)} is the end value.
	 */
	public void onDragEnd() {
		range.endSelectionBound();
	}

	/**
	 * Exposed so that when a {@link HexRow} gains hover-access it can notify the header to update
	 * its highlighted column to match.
	 *
	 * @return Header row.
	 */
	public HexRow getHeader() {
		return header;
	}

	/**
	 * @return Selection range.
	 */
	public HexRange getRange() {
		return range;
	}

	/**
	 * @return Backing hex data.
	 */
	public HexAccessor getHex() {
		return hex;
	}

	/**
	 * @return Number of columns.
	 */
	public int getHexColumns() {
		return hexColumns;
	}

	/**
	 * @return Number of rows.
	 */
	public int getHexRows() {
		return rowOffsets.size();
	}

	/**
	 * @return Virtual flow that holds each {@link HexRow}.
	 */
	public VirtualFlow<Integer, HexRow> getHexFlow() {
		return hexFlow;
	}

	/**
	 * @param text
	 * 		Text to format.
	 *
	 * @return Case formatted text.
	 */
	public static String caseHex(String text) {
		return text.toUpperCase();
	}

	/**
	 * Ensures that the current selection <i>({@link #range})</i> is shown to the user.
	 */
	private class SelectionHighlighter implements HexRangeListener {
		private int oldStart = -1;
		private int oldStop = -1;

		@Override
		public void onSelectionUpdate(int start, int stop) {
			if (oldStart != -1) {
				int min;
				int max;
				if (start > oldStart) {
					// Two scenarios:
					// - User is retracting start but selection is still backwards
					// - User is now making a forwards selection, prior backwards selection must be cleared
					min = oldStart;
					max = start - 1;
					handle(min, max, (row, localOffset) -> row.removeHoverEffect(localOffset, false, false));
				} else if (stop < oldStop) {
					// Two scenarios:
					// - User is retracting stop but selection is still forwards
					// - User is now making a backwards selection, prior forwards selection must be cleared
					min = stop + 1;
					max = oldStop;
					handle(min, max, (row, localOffset) -> row.removeHoverEffect(localOffset, false, false));
				}
			}
			handle(start, stop, (row, localOffset) -> row.addHoverEffect(localOffset, false, false));
			oldStart = start;
			oldStop = stop;
		}

		@Override
		public void onSelectionComplete(int start, int stop) {
			oldStart = -1;
			oldStop = -1;
		}

		@Override
		public void onSelectionClear(int start, int stop) {
			handle(start, stop, (row, localOffset) -> row.removeHoverEffect(localOffset, false, false));
			oldStart = -1;
			oldStop = -1;
		}

		private void handle(int start, int stop, BiConsumer<HexRow, Integer> action) {
			int incr = getHexColumns();
			int rowOffsetStart = start - (start % incr);
			int rowOffsetStop = Math.max(stop - (stop % incr), rowOffsetStart + incr);
			for (int rowOffset = rowOffsetStart; rowOffset <= rowOffsetStop; rowOffset += incr) {
				int itemIndex = rowOffset / incr;
				if (itemIndex >= rowOffsets.size())
					break;
				Optional<HexRow> rowAtIndex = hexFlow.getCellIfVisible(itemIndex);
				if (rowAtIndex.isPresent()) {
					HexRow row = rowAtIndex.get();
					for (int localOffset = 0; localOffset < incr; localOffset++) {
						int offset = rowOffset + localOffset;
						if (offset < start)
							continue;
						else if (offset > stop)
							break;
						action.accept(row, localOffset);
					}
				}
			}
		}
	}
}
