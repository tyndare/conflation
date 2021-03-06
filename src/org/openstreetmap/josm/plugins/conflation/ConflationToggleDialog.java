// License: GPL. For details, see LICENSE file.
// Copyright 2012 by Josh Doe and others.
package org.openstreetmap.josm.plugins.conflation;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.TableCellRenderer;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.SelectionChangedListener;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.event.AbstractDatasetChangedEvent;
import org.openstreetmap.josm.data.osm.event.DataChangedEvent;
import org.openstreetmap.josm.data.osm.event.DataSetListener;
import org.openstreetmap.josm.data.osm.event.NodeMovedEvent;
import org.openstreetmap.josm.data.osm.event.PrimitivesAddedEvent;
import org.openstreetmap.josm.data.osm.event.PrimitivesRemovedEvent;
import org.openstreetmap.josm.data.osm.event.RelationMembersChangedEvent;
import org.openstreetmap.josm.data.osm.event.TagsChangedEvent;
import org.openstreetmap.josm.data.osm.event.WayNodesChangedEvent;
import org.openstreetmap.josm.gui.OsmPrimitivRenderer;
import org.openstreetmap.josm.gui.PleaseWaitRunnable;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerAddEvent;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerChangeListener;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerOrderChangeEvent;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerRemoveEvent;
import org.openstreetmap.josm.gui.widgets.PopupMenuLauncher;
import org.openstreetmap.josm.io.OsmTransferException;
import org.openstreetmap.josm.plugins.conflation.config.SettingsDialog;
import org.openstreetmap.josm.plugins.utilsplugin2.replacegeometry.ReplaceGeometryException;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.InputMapUtils;
import org.openstreetmap.josm.tools.Shortcut;
import org.openstreetmap.josm.tools.UserCancelException;
import org.xml.sax.SAXException;

public class ConflationToggleDialog extends ToggleDialog
implements SelectionChangedListener, DataSetListener, SimpleMatchListListener, LayerChangeListener {

    public static final String TITLE_PREFIX = tr("Conflation");
    public static final String PREF_PREFIX = "conflation";
    final JTabbedPane tabbedPane;
    final JTable matchTable;
    final JList<OsmPrimitive> referenceOnlyList;
    final UnmatchedObjectListModel referenceOnlyListModel;
    final JList<OsmPrimitive> subjectOnlyList;
    final UnmatchedObjectListModel subjectOnlyListModel;
    ConflationLayer conflationLayer; // may be null
    final SimpleMatchesTableModel matchTableModel;
    SimpleMatchList matches = new SimpleMatchList();;
    SimpleMatchSettings settings;  // may be null
    final SettingsDialog settingsDialog; // null if headless
    final ConflateAction conflateAction;
    final SideButton conflateButton;
    final RemoveAction removeAction;
    final SideButton removeButton;
    final ZoomToListSelectionAction zoomToListSelectionAction;
    final SelectionPopup selectionPopup;

    // Keep track of conflation cases automatically removed (because of corresponding primitives removal), 
    // to be able to restore them (in case of Undo)
    private final HashSet<OsmPrimitive> primitivesRemovedReferenceOnly = new HashSet<>();
    private final HashSet<OsmPrimitive> primitivesRemovedSubjectOnly = new HashSet<>();
    private final HashMap<OsmPrimitive, SimpleMatch> primitivesRemovedMatchByReference = new HashMap<>();
    private final HashMap<OsmPrimitive, SimpleMatch> primitivesRemovedMatchBySubject = new HashMap<>();

    public ConflationToggleDialog(ConflationPlugin conflationPlugin) {
        // TODO: create shortcut?
        super(TITLE_PREFIX, "conflation.png", tr("Activates the conflation plugin"),
                null, 150);

        if (!GraphicsEnvironment.isHeadless()) {
            settingsDialog = new SettingsDialog();
            settingsDialog.setModalityType(Dialog.ModalityType.MODELESS);
            settingsDialog.addWindowListener(new WindowAdapter() {
    
                @Override
                public void windowClosed(WindowEvent e) {
                    // "Generate matches" was clicked
                    if (settingsDialog.getValue() == 1) {
                        clear(true, true, false);
                        settings = settingsDialog.getSettings();
                        settingsDialog.savePreferences();
                        performMatching();
                    }
                }
            });
        } else {
            settingsDialog = null;
        }

        // create table to show matches and allow multiple selections
        matchTableModel = new SimpleMatchesTableModel();
        matchTable = new JTable(matchTableModel);

        // add selection handler, to center/zoom view
        matchTable.getSelectionModel().addListSelectionListener(
                new MatchListSelectionHandler());
        matchTable.getColumnModel().getSelectionModel().addListSelectionListener(
                new MatchListSelectionHandler());

        // FIXME: doesn't work right now
        matchTable.getColumnModel().getColumn(0).setCellRenderer(new OsmPrimitivRenderer());
        matchTable.getColumnModel().getColumn(1).setCellRenderer(new OsmPrimitivRenderer());
        matchTable.getColumnModel().getColumn(4).setCellRenderer(new ColorTableCellRenderer("Tags"));

        matchTable.setRowSelectionAllowed(true);
        matchTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        matchTable.setAutoCreateRowSorter(true);

        referenceOnlyListModel = new UnmatchedObjectListModel();
        referenceOnlyList = new JList<>(referenceOnlyListModel);
        referenceOnlyList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        referenceOnlyList.setCellRenderer(new OsmPrimitivRenderer());
        referenceOnlyList.setTransferHandler(null); // no drag & drop

        subjectOnlyListModel = new UnmatchedObjectListModel();
        subjectOnlyList = new JList<>(subjectOnlyListModel);
        subjectOnlyList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        subjectOnlyList.setCellRenderer(new OsmPrimitivRenderer());
        subjectOnlyList.setTransferHandler(null); // no drag & drop

        //add popup menu for zoom on selection
        zoomToListSelectionAction = new ZoomToListSelectionAction();
        selectionPopup = new SelectionPopup();
        SelectionPopupMenuLauncher launcher = new SelectionPopupMenuLauncher();
        matchTable.addMouseListener(launcher);
        subjectOnlyList.addMouseListener(launcher);
        referenceOnlyList.addMouseListener(launcher);

        //on enter key zoom to selection
        InputMapUtils.addEnterAction(matchTable, zoomToListSelectionAction);
        InputMapUtils.addEnterAction(subjectOnlyList, zoomToListSelectionAction);
        InputMapUtils.addEnterAction(referenceOnlyList, zoomToListSelectionAction);

        DoubleClickHandler dblClickHandler = new DoubleClickHandler();
        matchTable.addMouseListener(dblClickHandler);
        referenceOnlyList.addMouseListener(dblClickHandler);
        subjectOnlyList.addMouseListener(dblClickHandler);

        tabbedPane = new JTabbedPane();
        tabbedPane.addTab(tr("Matches"), new JScrollPane(matchTable));
        tabbedPane.addTab(tr("Reference only"), new JScrollPane(referenceOnlyList));
        tabbedPane.addTab(tr("Subject only"), new JScrollPane(subjectOnlyList));

        conflateAction = new ConflateAction();
        conflateButton = new SideButton(conflateAction);
        // TODO: don't need this arrow box now, but likely will shortly
        // conflateButton.createArrow(new ActionListener() {
        //     @Override
        //     public void actionPerformed(ActionEvent e) {
        //         ConflatePopupMenu.launch(conflateButton);
        //     }
        // });

        removeAction = new RemoveAction();
        removeButton = new SideButton(removeAction);

        // add listeners to update enable state of buttons
        tabbedPane.addChangeListener(conflateAction);
        tabbedPane.addChangeListener(removeAction);
        referenceOnlyList.addListSelectionListener(conflateAction);
        referenceOnlyList.addListSelectionListener(removeAction);
        subjectOnlyList.addListSelectionListener(conflateAction);
        subjectOnlyList.addListSelectionListener(removeAction);

        UnmatchedListDataListener unmatchedListener = new UnmatchedListDataListener();
        subjectOnlyListModel.addListDataListener(unmatchedListener);
        referenceOnlyListModel.addListDataListener(unmatchedListener);

        createLayout(tabbedPane, false, Arrays.asList(new SideButton[]{
                new SideButton(new ConfigureAction()),
                conflateButton,
                removeButton
                // new SideButton("Replace Geometry", false),
                // new SideButton("Merge Tags", false),
                // new SideButton("Remove", false)
        }));
    }

    /* ---------------------------------------------------------------------------------- */
    /* SimpleMatchListListener                                                            */
    /* ---------------------------------------------------------------------------------- */

    @Override
    public void simpleMatchListChanged(SimpleMatchList list) {
        updateTabTitles();
    }

    @Override
    public void simpleMatchListIntervalAdded(SimpleMatchList list, int index0, int index1) {
        updateTabTitles();
    }

    @Override
    public void simpleMatchListIntervalRemoved(SimpleMatchList list, int index0, int index1) {
        updateTabTitles();
    }

    @Override
    public void simpleMatchSelectionChanged(Collection<SimpleMatch> selected) {
        // adjust table selection to match match list selection
        // FIXME: is this really where I should be doing this?

        // selection is the same, don't do anything
        Collection<SimpleMatch> tableSelection = getSelectedFromTable();
        if (tableSelection.containsAll(selected) && tableSelection.size() == selected.size())
            return;

        ListSelectionModel lsm = matchTable.getSelectionModel();
        lsm.setValueIsAdjusting(true);
        lsm.clearSelection();
        for (SimpleMatch c : selected) {
            int idx = matches.indexOf(c);
            lsm.addSelectionInterval(idx, idx);
        }
        lsm.setValueIsAdjusting(false);
    }

    private void updateTabTitles() {
        tabbedPane.setTitleAt(
                tabbedPane.indexOfComponent(matchTable.getParent().getParent()),
                tr(marktr("Matches ({0})"), matches.size()));
        tabbedPane.setTitleAt(
                tabbedPane.indexOfComponent(referenceOnlyList.getParent().getParent()),
                tr(marktr("Reference only ({0})"), referenceOnlyListModel.getSize()));
        tabbedPane.setTitleAt(
                tabbedPane.indexOfComponent(subjectOnlyList.getParent().getParent()),
                tr(marktr("Subject only ({0})"), subjectOnlyListModel.getSize()));
    }

    private Component getSelectedTabComponent() {
        return ((JScrollPane) tabbedPane.getSelectedComponent()).getViewport().getView();
    }

    private List<OsmPrimitive> getSelectedReferencePrimitives() {
        List<OsmPrimitive> selection = new ArrayList<>();
        if (tabbedPane == null || getSelectedTabComponent() == null)
            return selection;

        if (getSelectedTabComponent().equals(matchTable)) {
            for (SimpleMatch c : matches.getSelected()) {
                selection.add(c.getReferenceObject());
            }
        } else if (getSelectedTabComponent().equals(referenceOnlyList)) {
            selection.addAll(referenceOnlyList.getSelectedValuesList());
        }
        return selection;
    }

    private List<OsmPrimitive> getSelectedSubjectPrimitives() {
        List<OsmPrimitive> selection = new ArrayList<>();
        if (tabbedPane == null || getSelectedTabComponent() == null)
            return selection;

        if (getSelectedTabComponent().equals(matchTable)) {
            for (SimpleMatch c : matches.getSelected()) {
                selection.add(c.getSubjectObject());
            }
        } else if (getSelectedTabComponent().equals(subjectOnlyList)) {
            selection.addAll(subjectOnlyList.getSelectedValuesList());
        }
        return selection;
    }

    private Collection<OsmPrimitive> getAllSelectedPrimitives() {
        Collection<OsmPrimitive> allSelected = new HashSet<>();
        allSelected.addAll(getSelectedReferencePrimitives());
        allSelected.addAll(getSelectedSubjectPrimitives());
        return allSelected;
    }

    private void selectAllListSelectedPrimitives() {
        List<OsmPrimitive> refSelected = getSelectedReferencePrimitives();
        List<OsmPrimitive> subSelected = getSelectedSubjectPrimitives();

        //clear current selection and add list-selected primitives, handling both
        //same and different reference/subject layers
        settings.getReferenceDataSet().clearSelection();
        settings.getSubjectDataSet().clearSelection();
        DataSet.removeSelectionListener(this);
        try {
            settings.getReferenceDataSet().addSelected(refSelected);
            settings.getSubjectDataSet().addSelected(subSelected);
        } finally {
            DataSet.addSelectionListener(this);
        }
    }

    class DoubleClickHandler extends MouseAdapter {
        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() < 2 || !SwingUtilities.isLeftMouseButton(e))
                return;

            selectAllListSelectedPrimitives();
            // zoom/center on selection
            AutoScaleAction.zoomTo(getAllSelectedPrimitives());
        }
    }

    public class ConfigureAction extends JosmAction {

        public ConfigureAction() {
            // TODO: settle on sensible shortcuts
            super(tr("Configure"), "dialogs/settings", tr("Configure conflation options"),
                    null, false);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            settingsDialog.setVisible(true);
        }
    }

    @Override
    public void showNotify() {
        super.showNotify();
        DataSet.addSelectionListener(this);
        Main.getLayerManager().addLayerChangeListener(this);
    }

    @Override
    public void hideNotify() {
        super.hideNotify();
        DataSet.removeSelectionListener(this);
        Main.getLayerManager().removeLayerChangeListener(this);
        clear(true, true, true);
        settingsDialog.clear(true, true);
        this.settings = null;
    }

    private void clear(boolean shouldClearReference, boolean shouldClearSubject, boolean shouldRemoveConflationLayer) {
        if (shouldRemoveConflationLayer && (conflationLayer != null)) {
            if (Main.getLayerManager().containsLayer(conflationLayer)) {
                Main.getLayerManager().removeLayer(conflationLayer);
            }
            conflationLayer = null;
        }
        if (settings != null) {
            if (shouldClearReference) {
                DataSet dataSet = settings.getReferenceDataSet();
                if (dataSet != null) {
                    dataSet.removeDataSetListener(this);
                    settings.setReferenceDataSet(null);
                }
                settings.setReferenceLayer(null);
                settings.setReferenceSelection(null);
            }
            if (shouldClearSubject) {
                DataSet dataSet = settings.getSubjectDataSet();
                if (dataSet != null) {
                    dataSet.removeDataSetListener(this);
                    settings.setSubjectDataSet(null);
                }
                settings.setSubjectLayer(null);
                settings.setSubjectSelection(null);
            }
        }
        clearListsContentAndListeners();
    }

    private void clearListsContentAndListeners() {
        primitivesRemovedReferenceOnly.clear();
        primitivesRemovedSubjectOnly.clear();
        primitivesRemovedMatchByReference.clear();
        primitivesRemovedMatchBySubject.clear();
        matches.clear();
        matches.removeAllConflationListChangedListener();
        referenceOnlyListModel.clear();
        subjectOnlyListModel.clear();
        updateTabTitles();
    }

    private void setListsContentAddListnersAndLayer(SimpleMatchList matchList,
            Collection<OsmPrimitive> referenceOnlyList, Collection<OsmPrimitive> subjectOnlyList) {
        clearListsContentAndListeners();
        matches = matchList;
        matchTableModel.setMatches(matches);
        matches.addConflationListChangedListener(conflateAction);
        matches.addConflationListChangedListener(removeAction);
        matches.addConflationListChangedListener(this);
        // add conflation layer
        try {
            if (conflationLayer == null) {
                conflationLayer = new ConflationLayer(matches);
            }
            if (!Main.getLayerManager().containsLayer(conflationLayer)) {
                Main.getLayerManager().addLayer(conflationLayer);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(Main.parent, ex.toString(), "Error adding conflation layer", JOptionPane.ERROR_MESSAGE);
        }
        if (conflationLayer != null) {
            conflationLayer.setMatches(matches);
        }
        referenceOnlyListModel.addAll(referenceOnlyList);
        subjectOnlyListModel.addAll(subjectOnlyList);
        updateTabTitles();
        settings.getSubjectDataSet().addDataSetListener(ConflationToggleDialog.this);
        settings.getReferenceDataSet().addDataSetListener(ConflationToggleDialog.this);
    }

    /* ---------------------------------------------------------------------------------- */
    /* SelectionChangedListener                                                           */
    /* ---------------------------------------------------------------------------------- */

    @Override
    public void selectionChanged(Collection<? extends OsmPrimitive> newSelection) {
        if (newSelection.size() > 0) {
            referenceOnlyList.getSelectionModel().clearSelection();
            subjectOnlyList.getSelectionModel().clearSelection();
            matchTable.getSelectionModel().clearSelection();
            boolean ensureVisible = true;
            for (OsmPrimitive item: newSelection) {
                if (addObjectToSelection(item, ensureVisible)) {
                    ensureVisible = false;
                }
            }
        }
    }

    private boolean addObjectToSelection(OsmPrimitive object, boolean ensureVisible) {
        SimpleMatch c = matches.getMatchByReference(object);
        if (c == null) {
            c = matches.getMatchBySubject(object);
        }
        if (c != null) {
            int index = matches.indexOf(c);
            if (index >= 0) {
                index = matchTable.convertRowIndexToView(index);
                matchTable.getSelectionModel().addSelectionInterval(index, index);
                if (ensureVisible) {
                    tabbedPane.setSelectedIndex(0);
                    matchTable.scrollRectToVisible(new Rectangle(matchTable.getCellRect(index, 0, true)));
                }
                return true;
            }
        }
        int index = referenceOnlyListModel.indexOf(object);
        if (index >= 0) {
            referenceOnlyList.getSelectionModel().addSelectionInterval(index, index);
            if (ensureVisible) {
                tabbedPane.setSelectedIndex(1);
                referenceOnlyList.ensureIndexIsVisible(index);
            }
            return true;
        }
        index = subjectOnlyListModel.indexOf(object);
        if (index >= 0) {
            subjectOnlyList.getSelectionModel().addSelectionInterval(index, index);
            if (ensureVisible) {
                tabbedPane.setSelectedIndex(2);
                subjectOnlyList.ensureIndexIsVisible(index);
            }
            return true;
        }
        return false;
    }

    private Collection<SimpleMatch> getSelectedFromTable() {
        ListSelectionModel lsm = matchTable.getSelectionModel();
        Collection<SimpleMatch> selMatches = new HashSet<>();
        for (int i = lsm.getMinSelectionIndex(); i <= lsm.getMaxSelectionIndex(); i++) {
            if (lsm.isSelectedIndex(i) && (i < matches.size())) {
                int modelIndex = matchTable.convertRowIndexToModel(i);
                if (modelIndex < matches.size()) {
                    selMatches.add(matches.get(modelIndex));
                }
            }
        }
        return selMatches;
    }

    protected static class ConflateMenuItem extends JMenuItem implements ActionListener {
        public ConflateMenuItem(String name) {
            super(name);
            addActionListener(this); //TODO: is this needed?
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            //TODO: do something!
        }
    }

    protected static class ConflatePopupMenu extends JPopupMenu {

        public static void launch(Component parent) {
            JPopupMenu menu = new ConflatePopupMenu();
            Rectangle r = parent.getBounds();
            menu.show(parent, r.x, r.y + r.height);
        }

        public ConflatePopupMenu() {
            add(new ConflateMenuItem("Use reference geometry, reference tags"));
            add(new ConflateMenuItem("Use reference geometry, subject tags"));
            add(new ConflateMenuItem("Use subject geometry, reference tags"));
        }
    }

    class MatchListSelectionHandler implements ListSelectionListener {

        @Override
        public void valueChanged(ListSelectionEvent e) {
            matches.setSelected(getSelectedFromTable());
            Main.map.mapView.repaint();
        }
    }

    class ColorTableCellRenderer extends JLabel implements TableCellRenderer {

        private final String columnName;

        ColorTableCellRenderer(String column) {
            this.columnName = column;
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            Object columnValue = table.getValueAt(row, table.getColumnModel().getColumnIndex(columnName));

            if (value != null) {
                setText(value.toString());
            }
            if (isSelected) {
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
            } else {
                setBackground(table.getBackground());
                setForeground(table.getForeground());
                if ("Conflicts!".equals(columnValue)) {
                    setBackground(java.awt.Color.red);
                } else {
                    setBackground(java.awt.Color.green);
                }
            }
            return this;
        }
    }

    public static class LayerListCellRenderer extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                boolean cellHasFocus) {
            Layer layer = (Layer) value;
            JLabel label = (JLabel) super.getListCellRendererComponent(list, layer.getName(), index, isSelected,
                    cellHasFocus);
            Icon icon = layer.getIcon();
            label.setIcon(icon);
            label.setToolTipText(layer.getToolTipText());
            return label;
        }
    }

    /**
     * Command to delete selected matches.
     */
    static class RemoveMatchCommand extends Command {
        private final ArrayList<SimpleMatch> toRemove;
        private final SimpleMatchList matches;
        RemoveMatchCommand(SimpleMatchList matches, Collection<SimpleMatch> toRemove) {
            this.toRemove = new ArrayList<>(toRemove);
            this.matches = matches;
        }

        @Override
        public boolean executeCommand() {
            return matches.removeAll(toRemove);
        }

        @Override
        public void undoCommand() {
            matches.addAll(toRemove);
        }

        @Override
        public void fillModifiedData(Collection<OsmPrimitive> modified, Collection<OsmPrimitive> deleted, Collection<OsmPrimitive> added) {
        }

        @Override
        public String getDescriptionText() {
            return tr(marktr("Delete {0} conflation matches"), toRemove.size());
        }

        @Override
        public Icon getDescriptionIcon() {
            return ImageProvider.get("dialogs", "delete");
        }
    }

    static class RemoveUnmatchedObjectCommand extends Command {
        private final UnmatchedObjectListModel model;
        private final Collection<OsmPrimitive> objects;

        RemoveUnmatchedObjectCommand(UnmatchedObjectListModel model,
                Collection<OsmPrimitive> objects) {
            this.model = model;
            this.objects = objects;
        }

        @Override
        public boolean executeCommand() {
            return model.removeAll(objects);
        }

        @Override
        public void undoCommand() {
            model.addAll(objects);
        }

        @Override
        public void fillModifiedData(Collection<OsmPrimitive> modified, Collection<OsmPrimitive> deleted, Collection<OsmPrimitive> added) {
        }

        @Override
        public String getDescriptionText() {
            return tr(marktr("Remove {0} unmatched objects"), objects.size());
        }

        @Override
        public Icon getDescriptionIcon() {
            return ImageProvider.get("dialogs", "delete");
        }

        @Override
        public Collection<OsmPrimitive> getParticipatingPrimitives() {
            return objects;
        }
    }

    class RemoveAction extends JosmAction implements SimpleMatchListListener, ChangeListener, ListSelectionListener {

        RemoveAction() {
            super(tr("Remove"), "dialogs/delete", tr("Remove selected matches"),
                    null, false);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Component selComponent = getSelectedTabComponent();
            if (selComponent.equals(matchTable)) {
                Main.main.undoRedo.add(new RemoveMatchCommand(matches, matches.getSelected()));
            } else if (selComponent.equals(referenceOnlyList)) {
                Main.main.undoRedo.add(
                        new RemoveUnmatchedObjectCommand(referenceOnlyListModel,
                                referenceOnlyList.getSelectedValuesList()));
            } else if (selComponent.equals(subjectOnlyList)) {
                Main.main.undoRedo.add(
                        new RemoveUnmatchedObjectCommand(subjectOnlyListModel,
                                subjectOnlyList.getSelectedValuesList()));
            }
        }

        @Override
        public void updateEnabledState() {
            Component selComponent = getSelectedTabComponent();
            int selSize = 0;
            if (selComponent.equals(matchTable)) {
                selSize = matches.getSelected().size();
            } else if (selComponent.equals(referenceOnlyList)) {
                selSize = referenceOnlyList.getSelectedValuesList().size();
            } else if (selComponent.equals(subjectOnlyList)) {
                selSize = subjectOnlyList.getSelectedValuesList().size();
            }
            if (removeButton != null) {
                removeButton.setText((selSize > 1) ? tr(marktr("Remove ({0})"), selSize) : tr("Remove"));
            }
            setEnabled(selSize > 0);
        }

        /* ------------------------------------------------------------------------------ */
        /* SimpleMatchListListener                                                        */
        /* ------------------------------------------------------------------------------ */

        @Override
        public void simpleMatchListChanged(SimpleMatchList list) {
        }

        @Override
        public void simpleMatchListIntervalAdded(SimpleMatchList list, int index0, int index1) {
        }

        @Override
        public void simpleMatchListIntervalRemoved(SimpleMatchList list, int index0, int index1) {
        }

        @Override
        public void simpleMatchSelectionChanged(Collection<SimpleMatch> selected) {
            updateEnabledState();
        }

        /* ------------------------------------------------------------------------------ */
        /* ChangeListener                                                                 */
        /* ------------------------------------------------------------------------------ */

        @Override
        public void stateChanged(ChangeEvent ce) {
            updateEnabledState();
        }

        /* ------------------------------------------------------------------------------ */
        /* ListSelectionListener                                                          */
        /* ------------------------------------------------------------------------------ */

        @Override
        public void valueChanged(ListSelectionEvent lse) {
            updateEnabledState();
        }

    }

    class ConflateAction extends JosmAction implements SimpleMatchListListener, ChangeListener, ListSelectionListener {

        ConflateAction() {
            // TODO: make sure shortcuts make sense
            super(tr("Conflate"), "dialogs/conflation", tr("Conflate selected objects"),
                    Shortcut.registerShortcut("conflation:replace", tr("Conflation: {0}", tr("Replace")),
                            KeyEvent.VK_F, Shortcut.ALT_CTRL), false);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            DataSet.removeSelectionListener(ConflationToggleDialog.this);
            DataSet subjectDataSet = settings.getSubjectDataSet();
            if (subjectDataSet != null) {
                subjectDataSet.removeDataSetListener(ConflationToggleDialog.this);
            }
            try {
                if (getSelectedTabComponent().equals(matchTable))
                    conflateMatchActionPerformed();
                else if (getSelectedTabComponent().equals(referenceOnlyList))
                    conflateUnmatchedObjectActionPerformed();
            } finally {
                DataSet.addSelectionListener(ConflationToggleDialog.this);
                if (subjectDataSet != null) {
                    subjectDataSet.addDataSetListener(ConflationToggleDialog.this);
                }
            }
        }

        private void conflateUnmatchedObjectActionPerformed() {
            List<OsmPrimitive> unmatchedObjects = referenceOnlyList.getSelectedValuesList();
            Command cmd = new ConflateUnmatchedObjectCommand(settings.getReferenceLayer(),
                    settings.getSubjectLayer(), unmatchedObjects, referenceOnlyListModel);
            Main.main.undoRedo.add(cmd);
            // TODO: change layer and select newly copied objects?
        }

        private void conflateMatchActionPerformed() {
            SimpleMatch nextSelection = matches.findNextSelection();
            //List<Command> cmds = new LinkedList<>();
            try {
                // iterate over selected matches in reverse order since they will be removed as we go
                List<SimpleMatch> selMatches = new ArrayList<>(matches.getSelected());
                for (SimpleMatch c : selMatches) {
                    ConflateMatchCommand conflateCommand;
                    try {
                        conflateCommand = new ConflateMatchCommand(c, matches, settings);
                    } catch (UserCancelException ex) {
                        break;
                    }
                    // FIXME: how to chain commands which change relations? (see below)
                    //cmds.add(conflateCommand);
                    Main.main.undoRedo.add(conflateCommand);
                }
            } catch (UserCancelRuntimeException ex) {
                // Ignore
            } catch (ReplaceGeometryException ex) {
                JOptionPane.showMessageDialog(Main.parent,
                        ex.getMessage(), tr("Cannot replace geometry."), JOptionPane.INFORMATION_MESSAGE);
            }

            // FIXME: ReplaceGeometry changes relations, so can't put it in a SequenceCommand
            // if (cmds.size() == 1) {
            //     Main.main.undoRedo.add(cmds.iterator().next());
            // } else if (cmds.size() > 1) {
            //     SequenceCommand seqCmd = new SequenceCommand(tr(marktr("Conflate {0} objects"), cmds.size()), cmds);
            //     Main.main.undoRedo.add(seqCmd);
            // }

            if (matches.getSelected().isEmpty())
                matches.setSelected(nextSelection);
        }

        @Override
        public void updateEnabledState() {
            Component selComponent = getSelectedTabComponent();
            int selSize = 0;
            if (selComponent.equals(matchTable)) {
                selSize = matches.getSelected().size();
            } else if (selComponent.equals(referenceOnlyList)) {
                selSize = referenceOnlyList.getSelectedValuesList().size();
            }
            if (conflateButton != null) {
                conflateButton.setText((selSize > 1) ? tr(marktr("Conflate ({0})"), selSize) : tr("Conflate"));
            }
            setEnabled(selSize > 0);
        }

        /* ------------------------------------------------------------------------------ */
        /* SimpleMatchListListener                                                        */
        /* ------------------------------------------------------------------------------ */

        @Override
        public void simpleMatchListChanged(SimpleMatchList list) {
        }

        @Override
        public void simpleMatchListIntervalAdded(SimpleMatchList list, int index0, int index1) {
        }

        @Override
        public void simpleMatchListIntervalRemoved(SimpleMatchList list, int index0, int index1) {
        }

        @Override
        public void simpleMatchSelectionChanged(Collection<SimpleMatch> selected) {
            updateEnabledState();
        }

        /* ------------------------------------------------------------------------------ */
        /* ChangeListener                                                                 */
        /* ------------------------------------------------------------------------------ */

        @Override
        public void stateChanged(ChangeEvent ce) {
            updateEnabledState();
        }

        /* ------------------------------------------------------------------------------ */
        /* ListSelectionListener                                                          */
        /* ------------------------------------------------------------------------------ */

        @Override
        public void valueChanged(ListSelectionEvent lse) {
            updateEnabledState();
        }

    }

    /**
     * The action for zooming to the primitives which are currently selected in
     * the list (either matches or single primitives).
     *
     */
    class ZoomToListSelectionAction extends JosmAction implements ListSelectionListener {
        ZoomToListSelectionAction() {
            super(tr("Zoom to selected primitive(s)"), "dialogs/autoscale/selection", tr("Zoom to selected primitive(s)"),
                    null, false);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (matchTable == null)
                return;

            Collection<OsmPrimitive> sel = getAllSelectedPrimitives();
            if (sel.isEmpty())
                return;
            AutoScaleAction.zoomTo(sel);
        }

        @Override
        public void updateEnabledState() {
            setEnabled(!getAllSelectedPrimitives().isEmpty());
        }

        @Override
        public void valueChanged(ListSelectionEvent e) {
            updateEnabledState();
        }
    }

    /**
     * The action for selecting the primitives which are currently selected in
     * the list (either matches or single primitives).
     *
     */
    class SelectListSelectionAction extends JosmAction implements ListSelectionListener {
        SelectListSelectionAction() {
            super(tr("Select selected primitive(s)"), "dialogs/select", tr("Select the primitives currently selected in the list"),
                    null, false);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (matchTable == null)
                return;
            selectAllListSelectedPrimitives();
        }

        @Override
        public void updateEnabledState() {
            setEnabled(!getAllSelectedPrimitives().isEmpty());
        }

        @Override
        public void valueChanged(ListSelectionEvent e) {
            updateEnabledState();
        }
    }

    /**
     * The popup menu launcher
     */
    class SelectionPopupMenuLauncher extends PopupMenuLauncher {

        @Override
        public void launch(MouseEvent evt) {
            //if none selected, select row under cursor
            Component c = getSelectedTabComponent();
            if (getAllSelectedPrimitives().isEmpty()) {
                if (c == matchTable) {
                    //FIXME: this doesn't seem to be working
                    int row = matchTable.rowAtPoint(evt.getPoint());
                    matchTable.getSelectionModel().addSelectionInterval(row, row);
                } else if (c == subjectOnlyList || c == referenceOnlyList) {
                    int idx = ((JList<?>) c).locationToIndex(evt.getPoint());
                    if (idx < 0)
                        return;
                    ((JList<?>) c).setSelectedIndex(idx);
                }
            }

            selectionPopup.show(c, evt.getX(), evt.getY());
        }
    }

    /**
     * The popup menu for the selection list
     */
    class SelectionPopup extends JPopupMenu {
        SelectionPopup() {
            matchTable.getSelectionModel().addListSelectionListener(zoomToListSelectionAction);
            subjectOnlyList.addListSelectionListener(zoomToListSelectionAction);
            referenceOnlyList.addListSelectionListener(zoomToListSelectionAction);
            add(zoomToListSelectionAction);

            SelectListSelectionAction selectListSelectionAction = new SelectListSelectionAction();
            matchTable.getSelectionModel().addListSelectionListener(selectListSelectionAction);
            subjectOnlyList.addListSelectionListener(selectListSelectionAction);
            referenceOnlyList.addListSelectionListener(selectListSelectionAction);
            add(selectListSelectionAction);
        }
    }

    /* ---------------------------------------------------------------------------------- */
    /* DataSetListener                                                                    */
    /* ---------------------------------------------------------------------------------- */

    @Override
    public void primitivesAdded(PrimitivesAddedEvent event) {
        // In case of primitive re-added because of Undo action, restore the
        // corresponding conflation lists case.
        if (settings != null) {
            DataSet dataSet = event.getDataset();
            if (dataSet == settings.getReferenceDataSet()) {
                for (OsmPrimitive p : event.getPrimitives()) {
                    SimpleMatch m = primitivesRemovedMatchByReference.remove(p);
                    if (m != null) {
                        matches.add(m);
                    }
                    if (primitivesRemovedReferenceOnly.remove(p)) {
                        referenceOnlyListModel.addElement(p);
                    }
                }
            } else if (dataSet == settings.getSubjectDataSet()) {
                for (OsmPrimitive p : event.getPrimitives()) {
                    SimpleMatch m = primitivesRemovedMatchBySubject.remove(p);
                    if (m != null) {
                        matches.add(m);
                    }
                    if (primitivesRemovedSubjectOnly.remove(p)) {
                        subjectOnlyListModel.addElement(p);
                    }
                }
            }
        }
    }

    @Override
    public void primitivesRemoved(PrimitivesRemovedEvent event) {
        // Remove the corresponding cases from the conflation lists.
        if (settings != null) {
            DataSet dataSet = event.getDataset();
            if (dataSet == settings.getReferenceDataSet()) {
                for (OsmPrimitive p : event.getPrimitives()) {
                    SimpleMatch m = matches.getMatchByReference(p);
                    if (m != null) {
                        primitivesRemovedMatchByReference.put(p, m);
                        matches.remove(m);
                    }
                    if (referenceOnlyListModel.removeElement(p)) {
                        primitivesRemovedReferenceOnly.add(p);
                    }
                }
            } else if (dataSet == settings.getSubjectDataSet()) {
                for (OsmPrimitive p : event.getPrimitives()) {
                    SimpleMatch m = matches.getMatchBySubject(p);
                    if (m != null) {
                        primitivesRemovedMatchBySubject.put(p, m);
                        matches.remove(m);
                    }
                    if (subjectOnlyListModel.removeElement(p)) {
                        primitivesRemovedSubjectOnly.add(p);
                    }
                }
            }
        }
    }

    @Override
    public void tagsChanged(TagsChangedEvent event) {
    }

    @Override
    public void nodeMoved(NodeMovedEvent event) {
    }

    @Override
    public void wayNodesChanged(WayNodesChangedEvent event) {
    }

    @Override
    public void relationMembersChanged(RelationMembersChangedEvent event) {
    }

    @Override
    public void otherDatasetChange(AbstractDatasetChangedEvent event) {
    }

    @Override
    public void dataChanged(DataChangedEvent event) {
        // In case of primitive re-added because of Undo action, restore the
        // corresponding conflation lists case.
        if (settings != null) {
            DataSet dataSet = event.getDataset();
            if (dataSet == settings.getReferenceDataSet()) {
                primitivesRemovedMatchByReference.entrySet().removeIf(entry -> {
                    if (!entry.getKey().isDeleted()) {
                        matches.add(entry.getValue());
                        return true;
                    }
                    return false;
                });
                primitivesRemovedReferenceOnly.removeIf(osmPrimitive -> {
                    if (!osmPrimitive.isDeleted()) {
                        referenceOnlyListModel.addElement(osmPrimitive);
                        return true;
                    }
                    return false;
                });
            } else if (dataSet == settings.getSubjectDataSet()) {
                primitivesRemovedMatchBySubject.entrySet().removeIf(entry -> {
                    if (!entry.getKey().isDeleted()) {
                        matches.add(entry.getValue());
                        return true;
                    }
                    return false;
                });
                primitivesRemovedSubjectOnly.removeIf(osmPrimitive -> {
                    if (!osmPrimitive.isDeleted()) {
                        subjectOnlyListModel.addElement(osmPrimitive);
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    /* ---------------------------------------------------------------------------------- */
    /* LayerChangeListener                                                                */
    /* ---------------------------------------------------------------------------------- */

    @Override
    public void layerAdded(LayerAddEvent e) {
        // Do nothing
    }

    @Override
    public void layerRemoving(LayerRemoveEvent e) {
        Layer removedLayer = e.getRemovedLayer();
        if (settings != null) {
            boolean shouldclearReferenceSettings = removedLayer == settings.getReferenceLayer();
            boolean shouldclearSubjectSettings = removedLayer == settings.getSubjectLayer();
            if (shouldclearReferenceSettings || shouldclearSubjectSettings) {
                clear(shouldclearReferenceSettings, shouldclearSubjectSettings, true);
            }
        }
        this.settingsDialog.layerRemoving(e);
    }

    @Override
    public void layerOrderChanged(LayerOrderChangeEvent e) {
        // Do nothing
    }

    /**
     * Launch the matching computation in a PleaseWaitRunnable window.
     */
    private void performMatching() {
        Main.worker.submit(new PleaseWaitRunnable(tr("Generating matches")) {

            private SimpleMatchList computedMatches;
            private Collection<OsmPrimitive> referenceOnlyList;
            private Collection<OsmPrimitive> subjectOnlyList;
            boolean executionOk = false;

            @Override
            protected void realRun() throws SAXException, IOException, OsmTransferException {
                computedMatches = new SimpleMatchList();
                computedMatches.addAll(MatchesComputation.generateMatches(settings, getProgressMonitor()));
                if (!getProgressMonitor().isCanceled()) {
                    referenceOnlyList = settings.getReferenceSelection().stream().filter(
                            r -> !computedMatches.hasMatchForReference(r)).collect(Collectors.toList());
                    subjectOnlyList = settings.getSubjectSelection().stream().filter(
                            s -> !computedMatches.hasMatchForSubject(s)).collect(Collectors.toList());
                }
                executionOk = true;
            }

            @Override
            protected void finish() {
                if (!getProgressMonitor().isCanceled() && executionOk) {
                    setListsContentAddListnersAndLayer(computedMatches, referenceOnlyList, subjectOnlyList);
                }
            }

            @Override
            protected void cancel() {}
        });
    }

    class UnmatchedListDataListener implements ListDataListener {

        @Override
        public void intervalAdded(ListDataEvent lde) {
            updateTabTitles();
        }

        @Override
        public void intervalRemoved(ListDataEvent lde) {
            updateTabTitles();
        }

        @Override
        public void contentsChanged(ListDataEvent lde) {
            updateTabTitles();
        }
    }
}
