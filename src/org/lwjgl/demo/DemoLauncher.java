/*
 * Copyright LWJGL. All rights reserved.
 * Copyright Tue Ton. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.lwjgl.system.Platform;

/**
 * Graphical launcher for the LWJGL demos, implemented with SWT.
 * 
 * @author Tue Ton
 */
public class DemoLauncher {

    static final String DEMO_PACKAGE_PREFIX = "org.lwjgl.demo.";

    static Tree demosTree;
    static Browser descriptionBrowser;
    static Button launchButton;
    static Group bgfxOptionsPanel;
    static List<Button> renderers;
    static List<Button> processors;
    static Button useCallbacks;
    static Button useCustomAllocator;

    public static void main(String[] args) {
        //if demo class is specified, launch it and exit
        if (args.length > 0) {
            String[] demoArgs = new String[]{};
            if (args.length > 1) {
                demoArgs = Arrays.copyOfRange(args, 1, args.length);
            }
            //class name can omit the default "org.lwjgl.demo." package prefix
            if (args[0].startsWith(DEMO_PACKAGE_PREFIX)) {
                //assume fully-specified demo class name
                launchDemoInCurrentJVM(args[0], demoArgs);
            } else {
                //assume partially-specified demo class name
                launchDemoInCurrentJVM(DEMO_PACKAGE_PREFIX + args[0], demoArgs);
            }
            return;
        }

        Display display = new Display();
        Shell shell = new Shell(display);
        shell.setText("LWJGL Demo Launcher");
        shell.setLayout(new FillLayout());

        setUpMainContent(shell);
        shell.pack();
        //whatever the computed size of the main window,
        //increase its initial width to take into accounts the 2 vertical scrollbars' widths,
        //and to hide the ugly horizontal scrollbar of the browser widget
        //from being displayed, especially in Windows!
        Point size = shell.getSize();
        shell.setSize(size.x + 50, size.y);

        shell.open();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch())
                display.sleep();
        }
        display.dispose();
    }

    static class DemoData {
        String description;
        boolean bgfxDemo = false;
        String snapshot;
        String className;

        @Override
        public String toString() {
            String str = "desc=" + description + ", bgfxDemo=" + bgfxDemo;
            if (snapshot != null) str += ", snapshot=" + snapshot;
            if (className != null) str += ", class=" + className;
            return str;
        }
    }

    static void setUpMainContent(Shell shell) {
        SashForm form = new SashForm(shell, SWT.HORIZONTAL);
        form.setLayout(new FillLayout());

        //left panel
        Composite leftContainer = new Composite(form, SWT.NONE);
        leftContainer.setLayout(new GridLayout(1, false));
        setUpLeftPanel(leftContainer);

        //right panel
        Composite rightContainer = new Composite(form, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.verticalSpacing = 10;
        rightContainer.setLayout(layout);
        setUpRightPanel(rightContainer);

        form.setWeights(new int[] {40, 60});
    }

    static void setUpLeftPanel(Composite container) {
        Label label = new Label(container, SWT.NONE);
        label.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        label.setText("LWJGL Demos:");
        FontData fontData = label.getFont().getFontData()[0];
        Font font = new Font(label.getDisplay(), new FontData(fontData.getName(), fontData.getHeight(), SWT.BOLD));
        label.setFont(font);

        demosTree = setUpDemosTree(container);
    }

    static Tree setUpDemosTree(Composite container) {
        final Tree tree = new Tree(container, SWT.BORDER | SWT.V_SCROLL);
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Map<String, Properties> demosData = getDemosData();
        buildDemosTree(tree, demosData);

        tree.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                updateRightPanel(tree);
            }
        });

        //the tree's root items are expanded initially
        Stream.of(tree.getItems()).forEach(item -> item.setExpanded(true));

        return tree;
    }

    static void setUpRightPanel(Composite container) {
        Label label = new Label(container, SWT.NONE);
        label.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        label.setText("Description:");
        FontData fontData = label.getFont().getFontData()[0];
        Font font = new Font(label.getDisplay(), new FontData(fontData.getName(), fontData.getHeight(), SWT.BOLD));
        label.setFont(font);

        descriptionBrowser = new Browser(container, SWT.NONE);
        descriptionBrowser.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        bgfxOptionsPanel = setUpBGFXOptionsPanel(container);
        
        launchButton = new Button(container, SWT.PUSH);
        GridData gridData = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        gridData.verticalIndent = 10;
        launchButton.setLayoutData(gridData);
        launchButton.setText("Launch demo");
        launchButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                TreeItem item = demosTree.getSelection()[0];
                DemoData demoData = (DemoData) item.getData();
                if (demoData.className != null) {
                    List<String> args = new ArrayList<String>();
                    if (demoData.bgfxDemo) {
                        //some BGFX-specific arguments, as specified by the user
                        renderers.stream().filter(button -> button.getSelection())
                                          .forEach(button -> args.add((String) button.getData()));
                        processors.stream().filter(button -> button.getSelection())
                                           .forEach(button -> args.add((String) button.getData()));
                        if (useCallbacks.getSelection()) {
                            args.add((String) useCallbacks.getData());
                        }
                        if (useCustomAllocator.getSelection()) {
                            args.add((String) useCustomAllocator.getData());
                        }
                    }
                    launchDemoInSeparateJVM(DEMO_PACKAGE_PREFIX + demoData.className, args);
                }
            }
        });
    }

    static Group setUpBGFXOptionsPanel(Composite container) {
        Group optsGroup = new Group(container, SWT.SHADOW_ETCHED_IN);
        optsGroup.setLayout(new GridLayout(2, false));
        optsGroup.setText("BGFX options");
        FontData fontData = optsGroup.getFont().getFontData()[0];
        Font font = new Font(optsGroup.getDisplay(), new FontData(fontData.getName(), fontData.getHeight(), SWT.BOLD));
        optsGroup.setFont(font);

        //Renderer group
        Group group = new Group(optsGroup, SWT.NONE);
        group.setText("Renderer");
        group.setLayout(new RowLayout(SWT.VERTICAL));
        renderers = new ArrayList<Button>();
        Button button;
        if (Platform.get() == Platform.WINDOWS) {
            button = new Button(group, SWT.RADIO);
            button.setText("Direct3D 9");
            button.setData("--d3d9");
            renderers.add(button);
            button = new Button(group, SWT.RADIO);
            button.setText("Direct3D 11");
            button.setData("--d3d11");
            renderers.add(button);
            button = new Button(group, SWT.RADIO);
            button.setText("Direct3D 12");
            button.setData("--d3d12");
            renderers.add(button);
        } else if (Platform.get() == Platform.MACOSX) {
            button = new Button(group, SWT.RADIO);
            button.setText("Metal");
            button.setData("--mtl");
            renderers.add(button);
        }
        button = new Button(group, SWT.RADIO);
        button.setText("OpenGL");
        button.setData("--gl");
        renderers.add(button);
        button = new Button(group, SWT.RADIO);
        button.setText("Vulkan");
        button.setSelection(true); //default renderer
        button.setData("--vk");
        renderers.add(button);
        //does this --noop renderer option even work?
        button = new Button(group, SWT.RADIO);
        button.setText("No-op");
        button.setData("--noop");
        renderers.add(button);

        //Processor group
        group = new Group(optsGroup, SWT.NONE);
        group.setText("Processor");
        group.setLayout(new RowLayout(SWT.VERTICAL));
        processors = new ArrayList<Button>();
        button = new Button(group, SWT.RADIO);
        button.setText("AMD");
        button.setData("--amd");
        processors.add(button);
        button = new Button(group, SWT.RADIO);
        button.setText("NVIDIA");
        button.setData("--nvidia");
        processors.add(button);
        button = new Button(group, SWT.RADIO);
        button.setText("Intel");
        button.setData("--intel");
        processors.add(button);
        button = new Button(group, SWT.RADIO);
        button.setText("Software Emulated");
        button.setData("--sw");
        processors.add(button);

        useCallbacks = new Button(optsGroup, SWT.CHECK);
        useCallbacks.setText("Use callbacks");
        useCallbacks.setData("--cb");
        useCustomAllocator = new Button(optsGroup, SWT.CHECK);
        useCustomAllocator.setText("Use custom allocator");
        useCustomAllocator.setData("--alloc");

        return optsGroup;
    }

    static void updateRightPanel(Tree tree) {
        TreeItem item = tree.getSelection()[0];
        DemoData data = (DemoData) item.getData();

        if (data.className == null) {
            launchButton.setVisible(false);
        } else {
            launchButton.setVisible(true);
        }

        if (data.bgfxDemo) {
            //display the BGFX options panel
            if (bgfxOptionsPanel == null) {
                Composite parent = descriptionBrowser.getParent();
                bgfxOptionsPanel = setUpBGFXOptionsPanel(parent);
                bgfxOptionsPanel.moveBelow(descriptionBrowser);
                bgfxOptionsPanel.requestLayout();
            }
        } else {
            //hide the BGFX options panel
            if (bgfxOptionsPanel != null) {
                bgfxOptionsPanel.dispose();
                bgfxOptionsPanel = null;
                renderers = null;
                processors = null;
                useCallbacks = null;
                useCustomAllocator = null;
                descriptionBrowser.getParent().requestLayout();
            }
        }

        descriptionBrowser.setText(data.description);
        descriptionBrowser.requestLayout();
    }

    static void launchDemoInSeparateJVM(String demoClassName, List<String> args) {
        Class<?> demoClass;
        try {
            demoClass = Class.forName(demoClassName);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Unknown demo class: " + demoClassName, e);
        }

        ProcessHandle.Info currentProcessInfo = ProcessHandle.current().info();
        List<String> newProcessCommand = new LinkedList<>();
        newProcessCommand.add(currentProcessInfo.command().get());
        if (Platform.get() == Platform.MACOSX) {
            newProcessCommand.add("-XstartOnFirstThread");
        }
        //inherit all org.lwjgl.* system properties, if present
        for (Entry<Object, Object> prop : System.getProperties().entrySet()) {
            String key = (String) prop.getKey();
            if (key.startsWith("org.lwjgl")) {
                newProcessCommand.add("-D" + key + "=" + prop.getValue());
            }
        }
        String classpath = ManagementFactory.getRuntimeMXBean().getClassPath();
        //note: classpath is empty in the GraalVM native image at runtime
        if (classpath != null && !classpath.isEmpty()) {
            newProcessCommand.add("-classpath");
            newProcessCommand.add(classpath);
        }
        newProcessCommand.add(demoClass.getName());
        if (args != null) {
            newProcessCommand.addAll(args);
        }
        
        ProcessBuilder newProcessBuilder = new ProcessBuilder(newProcessCommand).inheritIO();
        try {
            Process newProcess = newProcessBuilder.start();
            System.out.format("%s: process %s started%n", demoClass.getName(), newProcessBuilder.command());
            System.out.format("%s: process exited with status %s%n", demoClass.getName(), newProcess.waitFor());
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    static void launchDemoInCurrentJVM(String demoClassName, String[] args) {
        try {
            Class<?> demoClass = Class.forName(demoClassName);
            Method mainMethod = demoClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object)args);
        } catch (ClassNotFoundException | NoSuchMethodException | SecurityException | 
                 IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    static Map<String, Properties> getDemosData() {
        InputStream is = DemoLauncher.class.getResourceAsStream("demos-data.properties");
        try (Reader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            Map<String, Properties> result = new TreeMap<>();
            //specialized Properties class to read INI-formatted properties file
            new Properties() {
                private static final long serialVersionUID = 1L;
                private Properties section;

                @Override
                public Object put(Object key, Object value) {
                    String header = (((String) key) + " " + value).trim();
                    if (header.startsWith("[") && header.endsWith("]"))
                        return result.put(header.substring(1, header.length() - 1), 
                                section = new Properties());
                    else
                        return section.put(key, value);
                }
            }.load(reader);

            return result;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void buildDemosTree(Tree tree, Map<String, Properties> demosData) {
        //add all tree paths in the demosData to the tree
        for (Entry<String, Properties> entry : demosData.entrySet()) {
            Properties props = entry.getValue();
            //each key is a visible tree path, with nodes separated by "."
            String[] nodes = entry.getKey().split("\\.");

            TreeItem lastTreeItem = null;
            int lastNodeIndex = -1;
            //each node should match a corresponding node in the tree,
            //so scan the nodes against the tree until a node isn't found in the tree
            //(indicating a starting new node to be added to the tree)
            for (int i = 0; i < nodes.length; i++) {
                TreeItem[] childItems;
                if (lastTreeItem == null) {
                    childItems = tree.getItems();
                } else {
                    childItems = lastTreeItem.getItems();
                }
                Boolean nodeFound = false;
                for (TreeItem childItem : childItems) {
                    if (childItem.getText().equals(nodes[i])) {
                        lastTreeItem = childItem;
                        lastNodeIndex = i;
                        nodeFound = true;
                        break;
                    }
                }
                if (!nodeFound) {
                    //skip the rest of the nodes when a node isn't found in the tree
                    break;
                }
            }
            //now add the rest of the nodes to the tree
            for (int i = lastNodeIndex+1; i < nodes.length; i++) {
                if (lastTreeItem == null) {
                    lastTreeItem = new TreeItem(tree, SWT.NONE);
                } else {
                    lastTreeItem = new TreeItem(lastTreeItem, SWT.NONE);
                }
                lastTreeItem.setText(nodes[i]);
                DemoData data = new DemoData();
                data.description = props.getProperty("description");
                data.className = props.getProperty("class");
                data.bgfxDemo = Boolean.valueOf(props.getProperty("bgfxDemo"));
                data.snapshot = props.getProperty("snapshot");
                lastTreeItem.setData(data);
            }
        }
    }

}
