/*
 * This software Copyright by the RPTools.net development team, and
 * licensed under the Affero GPL Version 3 or, at your option, any later
 * version.
 *
 * MapTool Source Code is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of the GNU Affero General Public
 * License * along with this source Code.  If not, please visit
 * <http://www.gnu.org/licenses/> and specifically the Affero license
 * text at <http://www.gnu.org/licenses/agpl.html>.
 */
package net.rptools.maptool.client;

import java.awt.EventQueue;
import java.awt.Point;
import java.awt.geom.Area;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;
import net.rptools.clientserver.hessian.AbstractMethodHandler;
import net.rptools.lib.MD5Key;
import net.rptools.maptool.client.functions.ExecFunction;
import net.rptools.maptool.client.functions.MacroLinkFunction;
import net.rptools.maptool.client.ui.MapToolFrame;
import net.rptools.maptool.client.ui.tokenpanel.InitiativePanel;
import net.rptools.maptool.client.ui.zone.FogUtil;
import net.rptools.maptool.client.ui.zone.ZoneRenderer;
import net.rptools.maptool.client.ui.zone.ZoneRendererFactory;
import net.rptools.maptool.language.I18N;
import net.rptools.maptool.model.Asset;
import net.rptools.maptool.model.AssetManager;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.model.CampaignProperties;
import net.rptools.maptool.model.CellPoint;
import net.rptools.maptool.model.ExposedAreaMetaData;
import net.rptools.maptool.model.GUID;
import net.rptools.maptool.model.Grid;
import net.rptools.maptool.model.InitiativeList;
import net.rptools.maptool.model.InitiativeList.TokenInitiative;
import net.rptools.maptool.model.Label;
import net.rptools.maptool.model.MacroButtonProperties;
import net.rptools.maptool.model.Pointer;
import net.rptools.maptool.model.TextMessage;
import net.rptools.maptool.model.Token;
import net.rptools.maptool.model.Zone;
import net.rptools.maptool.model.Zone.TopologyMode;
import net.rptools.maptool.model.Zone.VisionType;
import net.rptools.maptool.model.ZonePoint;
import net.rptools.maptool.model.drawing.Drawable;
import net.rptools.maptool.model.drawing.DrawnElement;
import net.rptools.maptool.model.drawing.Pen;
import net.rptools.maptool.model.framework.LibraryManager;
import net.rptools.maptool.model.framework.dropinlibrary.AddOnLibraryImporter;
import net.rptools.maptool.model.framework.dropinlibrary.TransferableAddOnLibrary;
import net.rptools.maptool.model.player.Player;
import net.rptools.maptool.server.Mapper;
import net.rptools.maptool.server.ServerMethodHandler;
import net.rptools.maptool.server.ServerPolicy;
import net.rptools.maptool.server.proto.*;
import net.rptools.maptool.transfer.AssetChunk;
import net.rptools.maptool.transfer.AssetConsumer;
import net.rptools.maptool.transfer.AssetHeader;
import org.apache.log4j.Logger;

/**
 * This class is used by the clients to receive server commands sent through {@link
 * ServerMethodHandler ServerMethodHandler}.
 *
 * @author drice
 */
public class ClientMethodHandler extends AbstractMethodHandler {
  private static final Logger log = Logger.getLogger(ClientMethodHandler.class);

  public ClientMethodHandler() {}

  @Override
  public void handleMessage(String id, byte[] message) {
    try {
      var msg = Message.parseFrom(message);
      var msgType = msg.getMessageTypeCase();
      log.info(id + ": p got: " + msgType);

      switch (msgType) {
        case ADD_TOPOLOGY_MSG -> handle(msg.getAddTopologyMsg());
        case BOOT_PLAYER_MSG -> handle(msg.getBootPlayerMsg());
        case CHANGE_ZONE_DISPLAY_NAME_MSG -> handle(msg.getChangeZoneDisplayNameMsg());
        case CLEAR_ALL_DRAWINGS_MSG -> handle(msg.getClearAllDrawingsMsg());
        case CLEAR_EXPOSED_AREA_MSG -> handle(msg.getClearExposedAreaMsg());
        case DRAW_MSG -> handle(msg.getDrawMsg());
        case EDIT_TOKEN_MSG -> handle(msg.getEditTokenMsg());
        case PUT_TOKEN_MSG -> handle(msg.getPutTokenMsg());
        case ENFORCE_NOTIFICATION_MSG -> handle(msg.getEnforceNotificationMsg());
        case ENFORCE_ZONE_MSG -> handle(msg.getEnforceZoneMsg());
        case ENFORCE_ZONE_VIEW_MSG -> handle(msg.getEnforceZoneViewMsg());
        case EXEC_FUNCTION_MSG -> handle(msg.getExecFunctionMsg());
        case EXEC_LINK_MSG -> handle(msg.getExecLinkMsg());
        case EXPOSE_FOW_MSG -> handle(msg.getExposeFowMsg());
        case EXPOSE_PC_AREA_MSG -> handle(msg.getExposePcAreaMsg());
        case HIDE_FOW_MSG -> handle(msg.getHideFowMsg());
        case HIDE_POINTER_MSG -> handle(msg.getHidePointerMsg());
        case MESSAGE_MSG -> handle(msg.getMessageMsg());
        case MOVE_POINTER_MSG -> handle(msg.getMovePointerMsg());
        default -> log.warn(msgType + "not handled.");
      }

    } catch (Exception e) {
      super.handleMessage(id, message);
    }
  }

  private void handle(MovePointerMsg msg) {
    EventQueue.invokeLater(() -> {
      Pointer pointer = MapTool.getFrame().getPointerOverlay().getPointer(msg.getPlayer());
      if (pointer == null) {
        return;
      }
      pointer.setX(msg.getX());
      pointer.setY(msg.getY());

      MapTool.getFrame().refresh();
    });
  }

  private void handle(MessageMsg msg) {
    EventQueue.invokeLater(() -> {
      TextMessage message = Mapper.map(msg.getMessage());
      MapTool.addServerMessage(message);
    });
  }

  private void handle(HidePointerMsg msg) {
    EventQueue.invokeLater(() -> {
      MapTool.getFrame().getPointerOverlay().removePointer(msg.getPlayer());
      MapTool.getFrame().refresh();
    });
  }

  private void handle(HideFowMsg msg) {
    EventQueue.invokeLater(() -> {
      var zoneGUID = GUID.valueOf(msg.getZoneGuid());
      var area = Mapper.map(msg.getArea());
      var selectedToks = msg.getTokenGuidList().stream().map(GUID::valueOf).collect(Collectors.toSet());

      var zone = MapTool.getCampaign().getZone(zoneGUID);
      zone.hideArea(area, selectedToks);
      MapTool.getFrame().refresh();
    });
  }

  private void handle(ExposePcAreaMsg msg) {
    EventQueue.invokeLater(() -> {
      var zoneGuid = GUID.valueOf(msg.getZoneGuid());
      var renderer = MapTool.getFrame().getZoneRenderer(zoneGuid);
      FogUtil.exposePCArea(renderer);
    });
  }

  private void handle(ExposeFowMsg msg) {
    EventQueue.invokeLater(() -> {
        var zoneGUID = GUID.valueOf(msg.getZoneGuid());
        Area area = Mapper.map(msg.getArea());
        var selectedToks = msg.getTokenGuidList().stream().map(GUID::valueOf).collect(Collectors.toSet());
        var zone = MapTool.getCampaign().getZone(zoneGUID);
        zone.exposeArea(area, selectedToks);
        MapTool.getFrame().refresh();
    });
  }

  private void handle(ExecLinkMsg msg) {
    EventQueue.invokeLater(() -> {
      MacroLinkFunction.receiveExecLink(
          msg.getLink(), msg.getTarget(), msg.getSource());
    });
  }

  private void handle(ExecFunctionMsg msg) {
    EventQueue.invokeLater(
        () -> {
          ExecFunction.receiveExecFunction(
              msg.getTarget(),
              msg.getSource(),
              msg.getFunctionName(),
              Mapper.map(msg.getArgumentList()));
        });
  }

  private void handle(EnforceZoneViewMsg msg) {
    EventQueue.invokeLater(
        () -> {
          var zoneGUID = GUID.valueOf(msg.getZoneGuid());
          int x = msg.getX();
          int y = msg.getY();
          double scale = msg.getScale();
          int gmWidth = msg.getGmWidth();
          int gmHeight = msg.getGmHeight();

          var renderer = MapTool.getFrame().getZoneRenderer(zoneGUID);
          if (renderer == null) {
            return;
          }
          if (AppPreferences.getFitGMView()) {
            renderer.enforceView(x, y, scale, gmWidth, gmHeight);
          } else {
            renderer.setScale(scale);
            renderer.centerOn(new ZonePoint(x, y));
          }
        });
  }

  private void handle(EnforceZoneMsg msg) {
    EventQueue.invokeLater(
        () -> {
          var zoneGUID = GUID.valueOf(msg.getZoneGuid());
          ZoneRenderer renderer = MapTool.getFrame().getZoneRenderer(zoneGUID);

          if (renderer != null
              && renderer != MapTool.getFrame().getCurrentZoneRenderer()
              && (renderer.getZone().isVisible() || MapTool.getPlayer().isGM())) {
            MapTool.getFrame().setCurrentZoneRenderer(renderer);
          }
        });
  }

  private void handle(EnforceNotificationMsg msg) {
    EventQueue.invokeLater(
        () -> {
          MapTool.getFrame().getCommandPanel().disableNotifyButton(msg.getEnforce());
        });
  }

  private void handle(PutTokenMsg putTokenMsg) {
    EventQueue.invokeLater(
        () -> {
          var zoneGUID = GUID.valueOf(putTokenMsg.getZoneGuid());
          var zone = MapTool.getCampaign().getZone(zoneGUID);
          var token = Mapper.map(putTokenMsg.getToken());
          zone.putToken(token);
          MapTool.getFrame().refresh();
        });
  }

  private void handle(EditTokenMsg editTokenMsg) {
    EventQueue.invokeLater(
        () -> {
          var zoneGUID = GUID.valueOf(editTokenMsg.getZoneGuid());
          var zone = MapTool.getCampaign().getZone(zoneGUID);
          var token = Mapper.map(editTokenMsg.getToken());
          zone.editToken(token);
          MapTool.getFrame().refresh();
        });
  }

  private void handle(DrawMsg drawMsg) {
    EventQueue.invokeLater(
        () -> {
          var zoneGuid = GUID.valueOf(drawMsg.getZoneGuid());
          Pen pen = Mapper.map(drawMsg.getPen());
          Drawable drawable = Mapper.map(drawMsg.getDrawable());

          var zone = MapTool.getCampaign().getZone(zoneGuid);
          zone.addDrawable(new DrawnElement(drawable, pen));
          MapTool.getFrame().refresh();
        });
  }

  private void handle(ClearExposedAreaMsg clearExposedAreaMsg) {
    EventQueue.invokeLater(
        () -> {
          var zoneGUID = GUID.valueOf(clearExposedAreaMsg.getZoneGuid());
          var zone = MapTool.getCampaign().getZone(zoneGUID);
          zone.clearExposedArea(clearExposedAreaMsg.getGlobalOnly());
        });
  }

  private void handle(ClearAllDrawingsMsg clearAllDrawingsMsg) {
    EventQueue.invokeLater(
        () -> {
          var zoneGUID = GUID.valueOf(clearAllDrawingsMsg.getZoneGuid());
          var layer = Zone.Layer.valueOf(clearAllDrawingsMsg.getLayer());

          var zone = MapTool.getCampaign().getZone(zoneGUID);
          zone.clearDrawables(zone.getDrawnElements(layer));
          MapTool.getFrame().refresh();
        });
  }

  private void handle(ChangeZoneDisplayNameMsg changeZoneDisplayNameMsg) {
    EventQueue.invokeLater(
        () -> {
          var zoneGUID = GUID.valueOf(changeZoneDisplayNameMsg.getZoneGuid());
          String dispName = changeZoneDisplayNameMsg.getName();

          var zone = MapTool.getCampaign().getZone(zoneGUID);
          if (zone != null) {
            zone.setPlayerAlias(dispName);
          }
          MapTool.getFrame()
              .setTitleViaRenderer(
                  MapTool.getFrame()
                      .getCurrentZoneRenderer()); // fixes a bug where the display name at the
          // program title was not updating
        });
  }

  private void handle(AddTopologyMsg addTopologyMsg) {
    var zoneGUID = GUID.valueOf(addTopologyMsg.getZoneGuid());
    var area = Mapper.map(addTopologyMsg.getArea());
    TopologyMode topologyMode = TopologyMode.valueOf(addTopologyMsg.getMode().name());

    var zone = MapTool.getCampaign().getZone(zoneGUID);
    zone.addTopology(area, topologyMode);

    MapTool.getFrame().getZoneRenderer(zoneGUID).repaint();
  }

  private void handle(BootPlayerMsg bootPlayerMsg) {
    String playerName = bootPlayerMsg.getPlayerName();
    if (MapTool.getPlayer().getName().equals(playerName))
      EventQueue.invokeLater(
          () -> {
            ServerDisconnectHandler.disconnectExpected = true;
            AppActions.disconnectFromServer();
            MapTool.showInformation("You have been booted from the server.");
          });
  }

  public void handleMethod(final String id, final String method, final Object... parameters) {
    final ClientCommand.COMMAND cmd = Enum.valueOf(ClientCommand.COMMAND.class, method);

    log.debug("from " + id + " got " + method);

    // These commands are safe to do in the background, any events that cause model updates need
    // to be on the EDT (See next section)
    switch (cmd) {
      case putAsset:
        AssetManager.putAsset((Asset) parameters[0]);
        MapTool.getFrame().getCurrentZoneRenderer().flushDrawableRenderer();
        MapTool.getFrame().refresh();
        return;

      case removeAsset:
        return;

      case startAssetTransfer:
        AssetHeader header = (AssetHeader) parameters[0];
        MapTool.getAssetTransferManager()
            .addConsumer(new AssetConsumer(AppUtil.getTmpDir(), header));
        return;

      case updateAssetTransfer:
        AssetChunk chunk = (AssetChunk) parameters[0];
        try {
          MapTool.getAssetTransferManager().update(chunk);
        } catch (IOException ioe) {
          // TODO: do something intelligent like clear the transfer
          // manager, and clear the "we're waiting for" flag so that it
          // gets requested again
          ioe.printStackTrace();
        }
        return;

      case addAddOnLibrary:
        var addedLibs = (List<TransferableAddOnLibrary>) parameters[0];
        for (var lib : addedLibs) {
          AssetManager.getAssetAsynchronously(
              lib.getAssetKey(),
              a -> {
                Asset asset = AssetManager.getAsset(a);
                try {
                  var addOnLibrary = new AddOnLibraryImporter().importFromAsset(asset);
                  new LibraryManager().reregisterAddOnLibrary(addOnLibrary);
                } catch (IOException e) {
                  SwingUtilities.invokeLater(
                      () -> {
                        MapTool.showError(
                            I18N.getText("library.import.error", lib.getNamespace()), e);
                      });
                }
              });
        }
        return;

      case removeAddOnLibrary:
        var remLibraryManager = new LibraryManager();
        var removedNamespaces = (List<String>) parameters[0];
        for (String namespace : removedNamespaces) {
          remLibraryManager.deregisterAddOnLibrary(namespace);
        }
        return;

      case removeAllAddOnLibraries:
        new LibraryManager().removeAddOnLibraries();
        return;
    }

    // Model events need to update on the EDT
    EventQueue.invokeLater(
        () -> {
          GUID zoneGUID;
          GUID tokenGUID;
          Zone zone;
          Token token;
          Set<GUID> selectedToks = null;
          List<GUID> tokenGUIDs;
          ZoneRenderer renderer;

          switch (cmd) {
            case setZoneHasFoW:
              zoneGUID = (GUID) parameters[0];
              boolean hasFog = (Boolean) parameters[1];

              zone = MapTool.getCampaign().getZone(zoneGUID);
              zone.setHasFog(hasFog);

              // In case we're looking at the zone
              MapTool.getFrame().refresh();
              return;

            case setFoW:
              zoneGUID = (GUID) parameters[0];
              var area = (Area) parameters[1];

              if (parameters.length > 2 && parameters[2] != null) {
                selectedToks = (Set<GUID>) parameters[2];
              }
              zone = MapTool.getCampaign().getZone(zoneGUID);
              zone.setFogArea(area, selectedToks);
              MapTool.getFrame().refresh();
              return;

            case setCampaign:
              Campaign campaign = (Campaign) parameters[0];
              MapTool.setCampaign(campaign);

              // Hide the "Connecting" overlay
              MapTool.getFrame().hideGlassPane();
              return;

            case setCampaignName:
              MapTool.getCampaign().setName((String) parameters[0]);
              MapTool.getFrame().setTitle();
              return;

            case putZone:
              zone = (Zone) parameters[0];
              MapTool.getCampaign().putZone(zone);

              // TODO: combine this with MapTool.addZone()
              renderer = ZoneRendererFactory.newRenderer(zone);
              MapTool.getFrame().addZoneRenderer(renderer);
              if (MapTool.getFrame().getCurrentZoneRenderer() == null && zone.isVisible()) {
                MapTool.getFrame().setCurrentZoneRenderer(renderer);
              }
              MapTool.getEventDispatcher()
                  .fireEvent(MapTool.ZoneEvent.Added, MapTool.getCampaign(), null, zone);
              return;

            case removeZone:
              zoneGUID = (GUID) parameters[0];
              MapTool.getCampaign().removeZone(zoneGUID);
              MapTool.getFrame().removeZoneRenderer(MapTool.getFrame().getZoneRenderer(zoneGUID));
              return;

            case putLabel:
              zoneGUID = (GUID) parameters[0];
              zone = MapTool.getCampaign().getZone(zoneGUID);
              Label label = (Label) parameters[1];
              zone.putLabel(label);
              MapTool.getFrame().refresh();
              return;

            case updateTokenProperty: // get token and update its property
              zoneGUID = (GUID) parameters[0];
              zone = MapTool.getCampaign().getZone(zoneGUID);
              tokenGUID = (GUID) parameters[1];
              token = zone.getToken(tokenGUID);
              if (token != null) {
                Token.Update update = (Token.Update) parameters[2];
                token.updateProperty(zone, update, (Object[]) parameters[3]);
              }
              return;

            case removeToken:
              zoneGUID = (GUID) parameters[0];
              zone = MapTool.getCampaign().getZone(zoneGUID);
              tokenGUID = (GUID) parameters[1];
              zone.removeToken(tokenGUID);
              MapTool.getFrame().refresh();
              return;

            case removeTokens:
              zoneGUID = (GUID) parameters[0];
              zone = MapTool.getCampaign().getZone(zoneGUID);
              tokenGUIDs = (List<GUID>) parameters[1];
              zone.removeTokens(tokenGUIDs);
              MapTool.getFrame().refresh();
              return;

            case removeLabel:
              zoneGUID = (GUID) parameters[0];
              zone = MapTool.getCampaign().getZone(zoneGUID);
              GUID labelGUID = (GUID) parameters[1];
              zone.removeLabel(labelGUID);
              MapTool.getFrame().refresh();
              return;

            case restoreZoneView:
              zoneGUID = (GUID) parameters[0];
              MapTool.getFrame().getZoneRenderer(zoneGUID).restoreView();
              return;

            case updateDrawing:
              zoneGUID = (GUID) parameters[0];
              Pen p = (Pen) parameters[1];
              DrawnElement de = (DrawnElement) parameters[2];

              zone = MapTool.getCampaign().getZone(zoneGUID);
              zone.updateDrawable(de, p);
              MapTool.getFrame().refresh();
              return;

            case undoDraw:
              zoneGUID = (GUID) parameters[0];
              GUID drawableId = (GUID) parameters[1];
              zone = MapTool.getCampaign().getZone(zoneGUID);
              if (zone == null) {
                return;
              }
              zone.removeDrawable(drawableId);
              if (MapTool.getFrame().getCurrentZoneRenderer().getZone().getId().equals(zoneGUID)
                  && zoneGUID != null) {
                MapTool.getFrame().refresh();
              }
              return;

            case setZoneVisibility:
              zoneGUID = (GUID) parameters[0];
              boolean visible = (Boolean) parameters[1];

              zone = MapTool.getCampaign().getZone(zoneGUID);
              zone.setVisible(visible);

              ZoneRenderer currentRenderer = MapTool.getFrame().getCurrentZoneRenderer();
              if (!visible
                  && !MapTool.getPlayer().isGM()
                  && currentRenderer != null
                  && currentRenderer.getZone().getId().equals(zoneGUID)) {
                MapTool.getFrame().setCurrentZoneRenderer(null);
              }
              if (visible && currentRenderer == null) {
                currentRenderer = MapTool.getFrame().getZoneRenderer(zoneGUID);
                MapTool.getFrame().setCurrentZoneRenderer(currentRenderer);
              }
              MapTool.getFrame().getZoneMiniMapPanel().flush();
              MapTool.getFrame().refresh();
              return;

            case setZoneGridSize:
              zoneGUID = (GUID) parameters[0];
              int xOffset = (Integer) parameters[1];
              int yOffset = (Integer) parameters[2];
              int size = (Integer) parameters[3];
              int color = (Integer) parameters[4];

              zone = MapTool.getCampaign().getZone(zoneGUID);
              zone.getGrid().setSize(size);
              zone.getGrid().setOffset(xOffset, yOffset);
              zone.setGridColor(color);

              MapTool.getFrame().refresh();
              return;

            case playerConnected:
              MapTool.addPlayer((Player) parameters[0]);
              MapTool.getFrame().refresh();
              return;

            case playerDisconnected:
              MapTool.removePlayer((Player) parameters[0]);
              MapTool.getFrame().refresh();
              return;


            case showPointer:
              MapTool.getFrame()
                  .getPointerOverlay()
                  .addPointer((String) parameters[0], (Pointer) parameters[1]);
              MapTool.getFrame().refresh();
              return;

            case startTokenMove:
              String playerId = (String) parameters[0];
              zoneGUID = (GUID) parameters[1];
              GUID keyToken = (GUID) parameters[2];
              Set<GUID> selectedSet = (Set<GUID>) parameters[3];

              renderer = MapTool.getFrame().getZoneRenderer(zoneGUID);
              renderer.addMoveSelectionSet(playerId, keyToken, selectedSet, true);
              return;

            case stopTokenMove:
              zoneGUID = (GUID) parameters[0];
              keyToken = (GUID) parameters[1];

              renderer = MapTool.getFrame().getZoneRenderer(zoneGUID);
              renderer.removeMoveSelectionSet(keyToken);
              return;

            case updateTokenMove:
              zoneGUID = (GUID) parameters[0];
              keyToken = (GUID) parameters[1];

              int x = (Integer) parameters[2];
              int y = (Integer) parameters[3];

              renderer = MapTool.getFrame().getZoneRenderer(zoneGUID);
              renderer.updateMoveSelectionSet(keyToken, new ZonePoint(x, y));
              return;

            case setTokenLocation:
              // Only the table should process this
              if (MapTool.getPlayer().getName().equalsIgnoreCase("Table")) {
                zoneGUID = (GUID) parameters[0];
                keyToken = (GUID) parameters[1];

                // This X,Y is the where the center of the token needs to be placed in
                // relation to the screen. So 0,0 would be top left which means only 1/4
                // of token would be drawn. 1024,768 would be lower right (on my table).
                x = (Integer) parameters[2];
                y = (Integer) parameters[3];

                // Get the zone
                zone = MapTool.getCampaign().getZone(zoneGUID);
                // Get the token
                token = zone.getToken(keyToken);

                Grid grid = zone.getGrid();
                // Convert the X/Y to the screen point
                renderer = MapTool.getFrame().getZoneRenderer(zone);
                CellPoint newPoint = renderer.getCellAt(new ScreenPoint(x, y));
                ZonePoint zp2 = grid.convert(newPoint);

                token.setX(zp2.x);
                token.setY(zp2.y);

                MapTool.serverCommand().putToken(zoneGUID, token);
              }
              return;

            case toggleTokenMoveWaypoint:
              zoneGUID = (GUID) parameters[0];
              keyToken = (GUID) parameters[1];
              ZonePoint zp = (ZonePoint) parameters[2];

              renderer = MapTool.getFrame().getZoneRenderer(zoneGUID);
              renderer.toggleMoveSelectionSetWaypoint(keyToken, zp);
              return;

            case setServerPolicy:
              ServerPolicy policy = (ServerPolicy) parameters[0];
              MapTool.setServerPolicy(policy);
              MapTool.getFrame().getToolbox().updateTools();
              return;

            case removeTopology:
              zoneGUID = (GUID) parameters[0];
              area = (Area) parameters[1];
              var topologyMode = (TopologyMode) parameters[2];

              zone = MapTool.getCampaign().getZone(zoneGUID);
              zone.removeTopology(area, topologyMode);

              MapTool.getFrame().getZoneRenderer(zoneGUID).repaint();
              return;

            case renameZone:
              zoneGUID = (GUID) parameters[0];
              String name = (String) parameters[1];

              zone = MapTool.getCampaign().getZone(zoneGUID);
              if (zone != null) {
                zone.setName(name);
              }
              MapTool.getFrame().setTitleViaRenderer(MapTool.getFrame().getCurrentZoneRenderer());
              return;

            case updateCampaign:
              CampaignProperties properties = (CampaignProperties) parameters[0];

              MapTool.getCampaign().replaceCampaignProperties(properties);
              MapToolFrame frame = MapTool.getFrame();
              ZoneRenderer zr = frame.getCurrentZoneRenderer();
              if (zr != null) {
                zr.getZoneView().flush();
                zr.repaint();
              }
              AssetManager.updateRepositoryList();

              InitiativePanel ip = frame.getInitiativePanel();
              ip.setOwnerPermissions(properties.isInitiativeOwnerPermissions());
              ip.setMovementLock(properties.isInitiativeMovementLock());
              ip.setInitUseReverseSort(properties.isInitiativeUseReverseSort());
              ip.setInitPanelButtonsDisabled(properties.isInitiativePanelButtonsDisabled());
              ip.updateView();
              MapTool.getFrame().getLookupTablePanel().updateView();
              return;

            case updateInitiative:
              InitiativeList list = (InitiativeList) parameters[0];
              Boolean ownerPermission = (Boolean) parameters[1];
              if (list != null) {
                zone = list.getZone();
                if (zone == null) return;
                zone.setInitiativeList(list);
              }
              if (ownerPermission != null) {
                MapTool.getFrame().getInitiativePanel().setOwnerPermissions(ownerPermission);
              }
              return;

            case updateTokenInitiative:
              zoneGUID = (GUID) parameters[0];
              tokenGUID = (GUID) parameters[1];
              zone = MapTool.getCampaign().getZone(zoneGUID);
              list = zone.getInitiativeList();
              TokenInitiative ti = list.getTokenInitiative((Integer) parameters[4]);
              if (!ti.getId().equals(tokenGUID)) {
                // Index doesn't point to same token, try to find it
                token = zone.getToken(tokenGUID);
                List<Integer> tokenIndex = list.indexOf(token);

                // If token in list more than one time, punt
                if (tokenIndex.size() != 1) return;
                ti = list.getTokenInitiative(tokenIndex.get(0));
              } // endif
              ti.update((Boolean) parameters[2], (String) parameters[3]);
              return;

            case setUseVision:
              zoneGUID = (GUID) parameters[0];
              VisionType visionType = (VisionType) parameters[1];
              zone = MapTool.getCampaign().getZone(zoneGUID);
              if (zone != null) {
                zone.setVisionType(visionType);
                if (MapTool.getFrame().getCurrentZoneRenderer() != null) {
                  MapTool.getFrame().getCurrentZoneRenderer().flushFog();
                  MapTool.getFrame().getCurrentZoneRenderer().getZoneView().flush();
                }
                MapTool.getFrame().refresh();
              }
              return;

            case setBoard:
              zoneGUID = (GUID) parameters[0];
              zone = MapTool.getCampaign().getZone(zoneGUID);

              Point boardXY = new Point((Integer) parameters[2], (Integer) parameters[3]);
              zone.setBoard(boardXY, (MD5Key) parameters[1]);
              return;

            case updateCampaignMacros:
              MapTool.getCampaign()
                  .setMacroButtonPropertiesArray(
                      new ArrayList<MacroButtonProperties>(
                          (ArrayList<MacroButtonProperties>) parameters[0]));
              MapTool.getFrame().getCampaignPanel().reset();
              return;

            case updateGmMacros:
              MapTool.getCampaign()
                  .setGmMacroButtonPropertiesArray(
                      new ArrayList<MacroButtonProperties>(
                          (ArrayList<MacroButtonProperties>) parameters[0]));
              MapTool.getFrame().getGmPanel().reset();
              return;

            case setLiveTypingLabel:
              if ((Boolean) parameters[1]) {
                // add a typer
                MapTool.getFrame()
                    .getChatNotificationTimers()
                    .setChatTyper(parameters[0].toString());
                return;
              } else {
                // remove typer from list
                MapTool.getFrame()
                    .getChatNotificationTimers()
                    .removeChatTyper(parameters[0].toString());
                return;
              }

            case updateExposedAreaMeta:
              zoneGUID = (GUID) parameters[0];
              tokenGUID = (GUID) parameters[1];
              ExposedAreaMetaData meta = (ExposedAreaMetaData) parameters[2];
              zone = MapTool.getCampaign().getZone(zoneGUID);
              zone.setExposedAreaMetaData(tokenGUID, meta);
              return;
          }
        });
  }
}
