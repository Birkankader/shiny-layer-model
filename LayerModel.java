import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Set;

    private IGISLayer rootAggGraphic = new LayerGraphic("root", null);
    private IGISLayer staticLayer = new StaticLayer(rootAggGraphic);
    private IGISLayer interactiveLayer = new LayerGraphic("Interactive", rootAggGraphic);
    private IGISLayer defaultLayer = new LayerGraphic("Default", rootAggGraphic);

    private IGISLayer cartographyAggGraphic = new LayerGraphic("Cartography", staticLayer);
    private IGISLayer drawingAggGraphic = new LayerGraphic("Drawing", interactiveLayer);
    private IGISLayer controllerGraphicsLayer = new ImmutableLayer("Controller", defaultLayer);
    private IGISLayer analysisLayer = new ImmutableLayer("Analysis", staticLayer);
    private IGISLayer pointAggGraphic;// = new LayerGraphic("Point", interactiveLayer);

    private IGISLayer dtedAggGraphic = null;
    private IGISLayer nitfAggGraphic = null;
    private IGISLayer ecdisAggGraphic = null;
    private IGISLayer wmsLayer = null;
    private IGISLayer wcsLayer = null;
    private IGISLayer wfsLayer;

    // Thread-safe event queue for graphic add/remove operations
    private final ConcurrentLinkedQueue<IGISGraphic> eventQueue = new ConcurrentLinkedQueue<>();
    // O(1) duplicate check for pending add events
    private final ConcurrentHashMap.KeySetView<String, Boolean> pendingNames = ConcurrentHashMap.newKeySet();

    private ArrayList<IGISGraphic> controllerGraphics = new ArrayList<>();
    private ArrayList<IGISGraphic> screenGraphics = new ArrayList<>();
    private final Set<IGISGraphic> screenGraphicSet = new HashSet<>();
    private Map<IGISGraphic, DrawableGraphicInfo> drawableGraphicInfoMap = new HashMap<>();

    private boolean addProcessFinished = false;

    public Map<IGISGraphic, DrawableGraphicInfo> getDrawableGraphicInfoMap() {
        return drawableGraphicInfoMap;
    }

    public LayerModel() {
    }

    private static final Logger LOGGER = LogManager.getLogger(LayerModel.class);

    private void processWaitingGraphicEvents(AGISGL gl) {
        // Batch processing - drain all events from queue
        List<IGISGraphic> batch = new ArrayList<>();
        IGISGraphic graphic;
        while ((graphic = eventQueue.poll()) != null) {
            batch.add(graphic);
            // Remove from pending set for add events (allows re-adding later)
            String name = graphic.getName();
            if (name != null) {
                pendingNames.remove(name);
            }
        }

        // Coalesce: cancel out add(0)/remove(1) pairs for the same graphic
        // Single-pass: IdentityHashMap avoids hashCode/equals overhead, null-out instead of rebuilding
        IdentityHashMap<IGISGraphic, int[]> seen = new IdentityHashMap<>();

        for (int i = 0; i < batch.size(); i++) {
            IGISGraphic g = batch.get(i);
            Integer eventType = (Integer) g.getClientProperty("Event");
            if (eventType == null || (eventType != 0 && eventType != 1)) continue;

            int[] prev = seen.get(g);
            if (prev != null && prev[1] != eventType) {
                batch.set(prev[0], null);
                batch.set(i, null);
                seen.remove(g);
            } else {
                seen.put(g, new int[]{i, eventType});
            }
        }

        // Process the batch
        for (IGISGraphic g : batch) {
            if (g == null) continue;
            try {
                Integer eventType = (Integer) g.getClientProperty("Event");
                if (eventType == null) eventType = 1;

                switch (eventType) {
                    case 0: addGraphic(g); break;
                    case 1: removeGraphic(g, gl); break;
                    case 2: removeControllerGraphicProcess(g, gl); break;
                    case 3: addControllerGraphicProcess(g); break;
                }
            } catch (Exception e) {
                LOGGER.error(e.getMessage());
            }
        }
    }


    private void addGraphic(IGISGraphic graphic) {
        try {
            IGISLayer targetLayer = (IGISLayer) graphic.getClientProperty("TargetLayer");
            if (targetLayer != null) {
                targetLayer.addChild(graphic);
                graphic.putClientProperty("TargetLayer", null);
            } else if (graphic.isScreenGraphic() && screenGraphicSet.add(graphic)) {
                screenGraphics.add(graphic);
            } else if (graphic instanceof IGISGraphicIcon) {
                pointAggGraphic.addChild(graphic);
            } else {
                if (!(graphic instanceof IGISLayer && ((IGISLayer) graphic).getRefreshRate() > 0)) {
                    getDrawingLayer().addChild(graphic);
                }
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
        }
    }

    private void removeGraphic(IGISGraphic graphic, AGISGL gl) {
        try {
            if (graphic != null) {
                ((AGraphic) graphic).deleteResources(gl);
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
        }
    }

    private void removeControllerGraphicProcess(IGISGraphic graphic, AGISGL gl) {
        try {
            if (graphic != null) {
                ((AGraphic) graphic).deleteResources(gl);
                if (graphic.isScreenGraphic()) {
                    screenGraphics.remove(graphic);
                    screenGraphicSet.remove(graphic);
                } else {
                    controllerGraphics.remove(graphic);
                    controllerGraphicsLayer.removeChild(graphic);
                }
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
        }
    }

    private void addControllerGraphicProcess(IGISGraphic graphic) {
        if (graphic.isScreenGraphic() && screenGraphicSet.add(graphic)) {
            screenGraphics.add(graphic);
        } else {
            controllerGraphics.add(graphic);
            controllerGraphicsLayer.addChild(graphic);
        }
    }

    public IGISGraphic add(IGISGraphic graphic) {
        if (graphic == null) {
            return null;
        }
        graphic.putClientProperty("Event", 0);
        // O(1) duplicate check using ConcurrentHashMap
        String name = graphic.getName();
        if (name != null && pendingNames.add(name)) {
            eventQueue.offer(graphic);
        } else if (name == null) {
            // Graphics without name are always added
            eventQueue.offer(graphic);
        }
        return graphic;
    }

    public IGISGraphic add(IGISGraphic graphic, IGISLayer layer) {
        if (graphic == null) {
            return null;
        }
        graphic.putClientProperty("Event", 0);
        graphic.putClientProperty("TargetLayer", layer);
        String name = graphic.getName();
        if (name != null && pendingNames.add(name)) {
            eventQueue.offer(graphic);
        } else if (name == null) {
            eventQueue.offer(graphic);
        }
        return graphic;
    }

    public IGISGraphic addControllerGraphic(IGISGraphic graphic) {
        if (graphic == null) {
            return null;
        }
        graphic.putClientProperty("Event", 3);
        eventQueue.offer(graphic);
        return graphic;
    }

    public void remove(IGISGraphic graphic) {
        if (graphic == cartographyAggGraphic || graphic == null) {
            return;
        }

        graphic.putClientProperty("Event", 1);
        eventQueue.offer(graphic);

        List<IGISGraphic> boundGraphics = graphic.getBoundGraphics();
        if (boundGraphics != null) {
            graphic.unbindAllGraphics();
            for (IGISGraphic boundGraphic : boundGraphics) {
                boundGraphic.putClientProperty("Event", 1);
                eventQueue.offer(boundGraphic);
            }
        }
    }

    public void removeControllerGraphic(IGISGraphic graphic) {
        if (graphic == null) {
            return;
        }
        graphic.putClientProperty("Event", 2);
        eventQueue.offer(graphic);
    }

    @Deprecated
    public List<IGISGraphic> getWaitingGraphicAddEvents() {
        return Collections.emptyList();
    }

    @Deprecated
    public List<IGISGraphic> getWaitingControllerGraphicAddEvents() {
        return Collections.emptyList();
    }

    public void processEvents(AGISGL gl) {
        processWaitingGraphicEvents(gl);
    }

    public ArrayList<IGISGraphic> getControllerGraphics() {
        return controllerGraphics;
    }

    public ArrayList<IGISGraphic> getScreenGraphics() {
        return screenGraphics;
    }

    @Override
    public IGISLayer getRoot() {
        return rootAggGraphic;
    }

    @Override
    public IGISLayer getDrawingLayer() {
        if (drawingAggGraphic.getParent() == null) {
            drawingAggGraphic = rootAggGraphic;
        }
        return drawingAggGraphic;
    }

    @Override
    public IGISLayer getPointsLayer() {
        return pointAggGraphic;
    }

    @Override
    public IGISLayer getDtedLayer() {
        if (dtedAggGraphic == null) {
            dtedAggGraphic = new LayerGraphic("Dted", cartographyAggGraphic);
        }
        return dtedAggGraphic;
    }

    @Override
    public IGISLayer getNitfLayer() {
        if (nitfAggGraphic == null) {
            nitfAggGraphic = new LayerGraphic("Nitf", cartographyAggGraphic);
        }
        return nitfAggGraphic;
    }

    @Override
    public IGISLayer getWebMapLayer() {
        if (wmsLayer == null) {
            wmsLayer = new LayerGraphic("WMS", cartographyAggGraphic);
        }
        return wmsLayer;
    }

    @Override
    public IGISLayer getEcdisMapLayer() {
        if (ecdisAggGraphic == null) {
            ecdisAggGraphic = new LayerGraphic("Ecdis", cartographyAggGraphic);
        }
        return ecdisAggGraphic;
    }

    @Override
    public IGISLayer getWebFeatureLayer() {
        if (wfsLayer == null) {
            wfsLayer = new LayerGraphic("WebWFS", cartographyAggGraphic);
        }
        return wfsLayer;
    }

    @Override
    public IGISLayer getWebCoverageLayer() {
        if (wcsLayer == null) {
            wcsLayer = new LayerGraphic("WCS", cartographyAggGraphic);
        }
        return wcsLayer;
    }

    @Override
    public IGISLayer getCartographyLayer() {
        return cartographyAggGraphic;
    }

    @Override
    public IGISLayer getAnalysisLayer() {
        if (null == analysisLayer) {
            analysisLayer = new ImmutableLayer("Analysis", staticLayer);
        }
        return analysisLayer;
    }

    @Override
    public void setDrawingLayer(IGISLayer layer) {
        layer.set3DRenderingProperty(RenderingProperty.ENABLE_DEPTH_MASK, true);

        if (drawingAggGraphic == layer) {
            return;
        }
        drawingAggGraphic = layer;
    }

    @Override
    public void setCartographyLayer(IGISLayer layer) {
        if (layer != null) {
            cartographyAggGraphic = layer;
        } else {
            cartographyAggGraphic = rootAggGraphic;
        }
    }

    @Override
    public void setNitfLayer(IGISLayer layer) {
        IGISGraphic[] children = nitfAggGraphic.getChildren();
        nitfAggGraphic = layer;
        nitfAggGraphic.setChildren(children);
    }

    @Override
    public void setDtedLayer(IGISLayer layer) {
        IGISGraphic[] children = dtedAggGraphic.getChildren();
        dtedAggGraphic = layer;
        dtedAggGraphic.setChildren(children);
    }

    @Override
    public void setEcdisLayer(IGISLayer layer) {
        ecdisAggGraphic = layer;
    }

    @Override
    public void setPointsLayer(IGISLayer layer) {
        if (pointAggGraphic != null) {
            rootAggGraphic.removeChild(pointAggGraphic);
            pointAggGraphic.dispose();
        }
        pointAggGraphic = layer;
        rootAggGraphic.addChild(pointAggGraphic);
    }
