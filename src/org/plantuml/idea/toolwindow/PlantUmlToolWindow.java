package org.plantuml.idea.toolwindow;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.messages.MessageBus;
import org.apache.batik.swing.JSVGCanvas;
import org.jetbrains.annotations.NotNull;
import org.plantuml.idea.action.SelectPageAction;
import org.plantuml.idea.plantuml.PlantUml;
import org.plantuml.idea.plantuml.PlantUmlResult;
import org.plantuml.idea.util.LazyApplicationPoolExecutor;
import org.plantuml.idea.util.UIUtils;

import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.io.File;

import static com.intellij.codeInsight.completion.CompletionInitializationContext.DUMMY_IDENTIFIER;

/**
 * @author Eugene Steinberg
 */
public class PlantUmlToolWindow extends JPanel {
    private static Logger logger = Logger.getInstance(PlantUmlToolWindow.class);

    private ToolWindow toolWindow;
    private Container imageLabel;
    private JSVGCanvas canvas;

    private PlantUmlResult result;

    private int zoom = 100;
    private int page = 0;
    private int numPages = 1;

    private String cachedSource = "";
    private int cachedPage = page;
    private int cachedZoom = zoom;

    private boolean zoomOnly;

    private FileEditorManagerListener plantUmlVirtualFileListener = new PlantUmlFileManagerListener();
    private DocumentListener plantUmlDocumentListener = new PlantUmlDocumentListener();
    private CaretListener plantUmlCaretListener = new PlantUmlCaretListener();
    private AncestorListener plantUmlAncestorListener = new PlantUmlAncestorListener();
    private ProjectManagerListener plantUmlProjectManagerListener = new PlantUmlProjectManagerListener();

    private LazyApplicationPoolExecutor lazyExecutor = new LazyApplicationPoolExecutor();

    private SelectPageAction selectPageAction;

    public PlantUmlToolWindow(Project myProject, ToolWindow toolWindow) {
        super(new BorderLayout());

        this.toolWindow = toolWindow;

        UIUtils.addProject(myProject, this);

        setupUI(myProject);

        registerListeners(myProject);
    }

    private void setupUI(final Project myProject) {
        ActionGroup group = (ActionGroup) ActionManager.getInstance().getAction("PlantUML.Toolbar");
        final ActionToolbar actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.UNKNOWN, group, true);
        actionToolbar.setTargetComponent(this);
        add(actionToolbar.getComponent(), BorderLayout.PAGE_START);

        canvas = new JSVGCanvas();
        canvas.setDisableInteractions(true);

        imageLabel = new JLabel();
        //imageLabel.setLayout(new BorderLayout());
        imageLabel.add(canvas, BorderLayout.CENTER);

        final JScrollPane scrollPane = new JBScrollPane(imageLabel);
        add(scrollPane, BorderLayout.CENTER);

        canvas.addMouseWheelListener(new MouseWheelListener() {
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.isControlDown()) {
                    setZoom(myProject, getZoom() - e.getWheelRotation() * 5);
                } else {
                    for (int i = 0; i < 10; i++) {
                        imageLabel.dispatchEvent(e);
                    }
                }

            }
        });

        canvas.addMouseMotionListener(new MouseMotionListener() {
            private int x, y;

            @Override
            public void mouseDragged(MouseEvent e) {
                JScrollBar h = scrollPane.getHorizontalScrollBar();
                JScrollBar v = scrollPane.getVerticalScrollBar();

                int dx = x - e.getXOnScreen();
                int dy = y - e.getYOnScreen();

                h.setValue(h.getValue() + dx);
                v.setValue(v.getValue() + dy);

                x = e.getXOnScreen();
                y = e.getYOnScreen();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                x = e.getXOnScreen();
                y = e.getYOnScreen();
            }
        });

        selectPageAction = (SelectPageAction) ActionManager.getInstance().getAction("PlantUML.SelectPage");
    }

    private void registerListeners(Project myProject) {
        logger.debug("Registering listeners");
        MessageBus messageBus = myProject.getMessageBus();
        messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, plantUmlVirtualFileListener);

        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(plantUmlDocumentListener);
        EditorFactory.getInstance().getEventMulticaster().addCaretListener(plantUmlCaretListener);

        toolWindow.getComponent().addAncestorListener(plantUmlAncestorListener);

        ProjectManager.getInstance().addProjectManagerListener(plantUmlProjectManagerListener);

        renderLater(myProject);
    }

    private void unregisterListeners() {
        EditorFactory.getInstance().getEventMulticaster().removeDocumentListener(plantUmlDocumentListener);
        EditorFactory.getInstance().getEventMulticaster().removeCaretListener(plantUmlCaretListener);

        toolWindow.getComponent().removeAncestorListener(plantUmlAncestorListener);

        ProjectManager.getInstance().removeProjectManagerListener(plantUmlProjectManagerListener);
    }

    private boolean renderRequired(String newSource) {
        if (newSource.isEmpty())
            return false;
        if (!newSource.equals(cachedSource) || page != cachedPage || zoom != cachedZoom) {
            zoomOnly = newSource.equals(cachedSource) && page == cachedPage;
            cachedSource = newSource;
            cachedPage = page;
            cachedZoom = zoom;
            return true;
        }
        return false;
    }

    private void renderLater(final Project project) {
        PlantUmlToolWindow toolWindow = UIUtils.getToolWindow(project);

        if (toolWindow != this) {
            if (toolWindow != null) {
                toolWindow.renderLater(project);
            }
            return;
        }

        ApplicationManager.getApplication().invokeLater(new Runnable() {
            @Override
            public void run() {
                if (!isProjectValid(project))
                    return;
                final String source = UIUtils.getSelectedSourceWithCaret(project);
                if (!renderRequired(source))
                    return;
                final File selectedDir = UIUtils.getSelectedDir(project);
                lazyExecutor.execute(
                        new Runnable() {
                            @Override
                            public void run() {
                                renderWithBaseDir(project, source, selectedDir, page);
                            }
                        }
                );
            }
        });
    }

    private void renderWithBaseDir(final Project myProject, final String source, final File selectedDir, final int page) {
        try {
            if (source.isEmpty())
                return;

            if (!zoomOnly) {
                result = PlantUml.render(source, selectedDir, page);
                canvas.setSVGDocument(result.getDocument());
            }

            ApplicationManager.getApplication().invokeLater(new Runnable() {
                public void run() {
                    if (!zoomOnly) {
                        setNumPages(myProject, result.getPages());
                    }

                    Dimension dimension = new Dimension(result.getWidth() * zoom / 100, result.getHeight() * zoom / 100);
                    if  (!dimension.equals(canvas.getSize())) {
                        imageLabel.setPreferredSize(dimension);
                        imageLabel.getParent().doLayout();

                        canvas.setSize(dimension);
                    }

                }
            });

        } catch (Exception e) {
            logger.warn("Exception occurred rendering source = " + source + ": " + e);
        }
    }

    public int getZoom() {
        return zoom;
    }

    public void setZoom(Project myProject, int zoom) {
        this.zoom = zoom;
        renderLater(myProject);
    }

    public void setPage(Project myProject, int page) {
        if (page >= 0 && page < numPages) {
            this.page = page;
            selectPageAction.setPage(page);
            renderLater(myProject);
        }
    }

    public void nextPage(Project myProject) {
        setPage(myProject, this.page + 1);
    }

    public void prevPage(Project myProject) {
        setPage(myProject, this.page - 1);
    }

    public void setNumPages(Project myProject, int numPages) {
        this.numPages = numPages;
        if (page >= numPages)
            setPage(myProject, numPages - 1);
        selectPageAction.setNumPages(numPages);
    }

    private class PlantUmlFileManagerListener implements FileEditorManagerListener {
        public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
            logger.debug("file opened " + file);
        }

        public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
            logger.debug("file closed = " + file);
        }

        public void selectionChanged(@NotNull FileEditorManagerEvent event) {
            logger.debug("selection changed" + event);
            renderLater(event.getManager().getProject());
        }
    }

    private class PlantUmlDocumentListener implements DocumentListener {

        public void beforeDocumentChange(DocumentEvent event) {
        }

        public void documentChanged(DocumentEvent event) {
            logger.debug("document changed " + event);
            //#18 Strange "IntellijIdeaRulezzz" - filter code completion event.
            if (!DUMMY_IDENTIFIER.equals(event.getNewFragment().toString())) {
                Editor[] editors = EditorFactory.getInstance().getEditors(event.getDocument());
                for (Editor editor : editors) {
                    renderLater(editor.getProject());
                }
            }
        }
    }

    private boolean isProjectValid(Project project) {
        return project != null && !project.isDisposed();
    }

    private class PlantUmlCaretListener implements CaretListener {
        @Override
        public void caretPositionChanged(final CaretEvent e) {
            renderLater(e.getEditor().getProject());
        }
    }

    private class PlantUmlAncestorListener implements AncestorListener {
        @Override
        public void ancestorAdded(AncestorEvent ancestorEvent) {
            Project[] projects = ProjectManager.getInstance().getOpenProjects();
            for (Project project : projects) {
                renderLater(project);
            }
        }

        @Override
        public void ancestorRemoved(AncestorEvent ancestorEvent) {
            // do nothing
        }

        @Override
        public void ancestorMoved(AncestorEvent ancestorEvent) {
            // do nothing

        }
    }

    private class PlantUmlProjectManagerListener implements ProjectManagerListener {
        @Override
        public void projectOpened(Project project) {
            logger.debug("opened project " + project);
            registerListeners(project);
        }

        @Override
        public boolean canCloseProject(Project project) {
            return true;
        }

        @Override
        public void projectClosed(Project project) {
            logger.debug("closed project " + project);
        }

        @Override
        public void projectClosing(Project project) {
            UIUtils.removeProject(project);
            unregisterListeners();
        }
    }
}

