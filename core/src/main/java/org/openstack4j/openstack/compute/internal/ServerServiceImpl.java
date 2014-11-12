package org.openstack4j.openstack.compute.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.openstack4j.api.compute.ServerService;
import org.openstack4j.core.transport.HttpResponse;
import org.openstack4j.model.compute.Action;
import org.openstack4j.model.compute.ActionResponse;
import org.openstack4j.model.compute.RebootType;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.Server.Status;
import org.openstack4j.model.compute.ServerCreate;
import org.openstack4j.model.compute.VNCConsole;
import org.openstack4j.model.compute.VNCConsole.Type;
import org.openstack4j.model.compute.VolumeAttachment;
import org.openstack4j.model.compute.actions.BackupOptions;
import org.openstack4j.model.compute.actions.LiveMigrateOptions;
import org.openstack4j.model.compute.actions.RebuildOptions;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;
import org.openstack4j.openstack.common.Metadata;
import org.openstack4j.openstack.compute.domain.ConsoleOutput;
import org.openstack4j.openstack.compute.domain.NovaServer;
import org.openstack4j.openstack.compute.domain.NovaServer.Servers;
import org.openstack4j.openstack.compute.domain.NovaServerCreate;
import org.openstack4j.openstack.compute.domain.NovaVNCConsole;
import org.openstack4j.openstack.compute.domain.NovaVolumeAttachment;
import org.openstack4j.openstack.compute.functions.ToActionResponseFunction;
import org.openstack4j.openstack.compute.functions.WrapServerIfApplicableFunction;

/**
 * Server Operation API implementation
 * 
 * @author Jeremy Unruh
 */
public class ServerServiceImpl extends BaseComputeServices implements ServerService {

    /**
     * {@inheritDoc}
     */
    @Override
    public List<? extends Server> list() {
        return list(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<? extends Server> list(boolean detail) {
        return list(detail, Boolean.FALSE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<? extends Server> listAll(boolean detail) {
        return list(detail, Boolean.TRUE);
    }

    private List<? extends Server> list(boolean detail, boolean allTenants) {
        Invocation<Servers> req = get(Servers.class, uri("/servers" + ((detail) ? "/detail" : "")));
        if (allTenants)
            req.param("all_tenants", 1);
        return req.execute().getList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<? extends Server> list(Map<String, String> filteringParams) {
        Invocation<Servers> serverInvocation = get(Servers.class, "/servers/detail");
        if (filteringParams != null) {
            for (Map.Entry<String, String> entry : filteringParams.entrySet()) {
                serverInvocation = serverInvocation.param(entry.getKey(), entry.getValue());
            }
        }
        return serverInvocation.execute().getList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Server get(String serverId) {
        checkNotNull(serverId);
        return get(NovaServer.class, uri("/servers/%s", serverId)).execute();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Server boot(ServerCreate server) {
        checkNotNull(server);
        return post(NovaServer.class, uri("/servers"))
                     .entity(WrapServerIfApplicableFunction.INSTANCE.apply(server))
                     .execute();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Server bootAndWaitActive(ServerCreate server, int maxWaitTime) {
        return waitForServerStatus(boot(server).getId(), Status.ACTIVE, maxWaitTime, TimeUnit.MILLISECONDS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ActionResponse delete(String serverId) {
        checkNotNull(serverId);
        return ToActionResponseFunction.INSTANCE.apply(
                    delete(Void.class, uri("/servers/%s", serverId)).executeWithResponse()
               );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ActionResponse action(String serverId, Action action) {
        checkNotNull(serverId);

        switch (action) {
        case PAUSE: return invokeAction(serverId, "pause");
        case UNPAUSE: return invokeAction(serverId, "unpause");
        case LOCK: return invokeAction(serverId, "lock");
        case UNLOCK: return invokeAction(serverId, "unlock");
        case START: return invokeAction(serverId, "os-start");
        case STOP: return invokeAction(serverId, "os-stop");
        case RESUME: return invokeAction(serverId, "resume");
        case RESCUE: return invokeAction(serverId, "rescue");
        case UNRESCUE: return invokeAction(serverId, "unrescue");
        case SHELVE: return invokeAction(serverId, "shelve");
        case SHELVE_OFFLOAD: return invokeAction(serverId, "shelveOffload");
        case UNSHELVE: return invokeAction(serverId, "unshelve");
        case SUSPEND: return invokeAction(serverId, "suspend");
        default:
            return ActionResponse.actionFailed(String.format("Action %s was not found in the list of invokable actions", action));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String createSnapshot(String serverId, String snapshotName) {
        checkNotNull(serverId);
        checkNotNull(snapshotName);

        String body = String.format("{ \"name\": \"%s\", \"metadata\":{} }", snapshotName);
        HttpResponse response = executeActionWithResponse(serverId, "createImage", body);
        if (response.getStatus() == 202) {
            String location = response.header("location");
            if (location != null && location.contains("/"))
            {
                String[] s = location.split("/");
                return s[s.length - 1];
            }

        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ActionResponse reboot(String serverId, RebootType type) {
        checkNotNull(serverId);

        String innerJson = String.format("{ \"type\": \"%s\" }", type.name());
        return invokeAction(serverId, "reboot", innerJson);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ActionResponse rebuild(String serverId, RebuildOptions options) {
        checkNotNull(serverId);
        return invokeAction(serverId, "rebuild", (options != null) ? options.toJsonString() : null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ActionResponse resize(String serverId, String flavorId) {
        checkNotNull(serverId);
        checkNotNull(flavorId);
        return invokeAction(serverId, "resize", String.format("{ \"flavorRef\": \"%s\" }", flavorId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ActionResponse addSecurityGroup(String serverId, String secGroupName) {
        checkNotNull(serverId);
        checkNotNull(secGroupName);
        return invokeAction(serverId, "addSecurityGroup", String.format("{ \"name\": \"%s\" }", secGroupName));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ActionResponse removeSecurityGroup(String serverId, String secGroupName) {
        checkNotNull(serverId);
        checkNotNull(secGroupName);
        return invokeAction(serverId, "removeSecurityGroup", String.format("{ \"name\": \"%s\" }", secGroupName));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ActionResponse confirmResize(String serverId) {
        checkNotNull(serverId);
        return invokeAction(serverId, "confirmResize");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ActionResponse revertResize(String serverId) {
        checkNotNull(serverId);
        return invokeAction(serverId, "revertResize");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getConsoleOutput(String serverId, int numLines) {
        checkNotNull(serverId);
        if (numLines <= 0)
            numLines = 50;

        ConsoleOutput c = post(ConsoleOutput.class, uri("/servers/%s/action", serverId)).json(ConsoleOutput.getJSONAction(numLines)).execute();
        return (c != null) ? c.getOutput() : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VNCConsole getVNCConsole(String serverId, Type type) {
        checkNotNull(serverId);
        if (type == null)
            type = Type.NOVNC;

        return post(NovaVNCConsole.class, uri("/servers/%s/action", serverId)).json(NovaVNCConsole.getJSONAction(type)).execute();
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    public Map<String, ? extends Number> diagnostics(String serverId) {
        return get(HashMap.class, uri("/servers/%s/diagnostics", serverId)).execute();
    }

    private ActionResponse invokeAction(String serverId, String action) {
        return invokeAction(serverId, action, null);
    }

    private ActionResponse invokeAction(String serverId, String action, String innerJson) {
        HttpResponse response = executeActionWithResponse(serverId, action, innerJson);
        return ToActionResponseFunction.INSTANCE.apply(response, action);
    }

    private HttpResponse executeActionWithResponse(String serverId, String action, String innerJson) {
        return post(Void.class, uri("/servers/%s/action", serverId)).json(String.format("{ \"%s\": %s }", action, (innerJson != null) ? innerJson : "null")).executeWithResponse();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ServerCreateBuilder serverBuilder() {
        return NovaServerCreate.builder();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VolumeAttachment attachVolume(String serverId, String volumeId) {
        String body = String.format("{\"volumeAttachment\":{ \"volumeId\": \"%s\" }}", volumeId);
        return post(NovaVolumeAttachment.class, uri("/servers/%s/os-volume_attachments", serverId)).json(body).execute();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ActionResponse detachVolume(String serverId, String attachmentId) {
        return ToActionResponseFunction.INSTANCE.apply(
                   delete(Void.class,uri("/servers/%s/os-volume_attachments/%s", serverId, attachmentId)).executeWithResponse()
                );
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public ActionResponse migrateServer(String serverId) {
        checkNotNull(serverId);
        return invokeAction(serverId, "migrate");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ActionResponse liveMigrate(String serverId, LiveMigrateOptions options) {
        checkNotNull(serverId);
        if (options == null)
            options = LiveMigrateOptions.create();
        return invokeAction(serverId, "os-migrateLive", options.toJsonString());
    }
    
    /**
     * {{@link #invokeAction(String, String)}
     */
    @Override
    public ActionResponse backupServer(String serverId, BackupOptions options) {
        checkNotNull(serverId);
        checkNotNull(options);
        return invokeAction(serverId, "createBackup", options.toJsonString());
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public ActionResponse changeAdminPassword(String serverId, String adminPassword) {
        checkNotNull(serverId);
        checkNotNull(adminPassword);
        String json = String.format("{ \"adminPass\": \"%s\" }", adminPassword);
        return invokeAction(serverId, "changePassword",json);
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public Server waitForServerStatus(String serverId, Status status, int maxWait, TimeUnit maxWaitUnit) {
        checkNotNull(serverId);
        Server server = null;
        long duration = 0;
        long maxTime = maxWaitUnit.toMillis(maxWait);
        while ( duration < maxTime ) {
            server = get(serverId);
            
            if (server == null || server.getStatus() == status || server.getStatus() == Status.ERROR)
                break;
            
            duration += sleep(1000);
        }
        return server;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, String> getMetadata(String serverId) {
        checkNotNull(serverId);
        return get(Metadata.class, uri("/servers/%s/metadata", serverId)).execute();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, String> updateMetadata(String serverId, Map<String, String> metadata) {
        checkNotNull(serverId);
        checkNotNull(metadata);
        return put(Metadata.class, uri("/servers/%s/metadata", serverId)).entity(Metadata.toMetadata(metadata)).execute();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ActionResponse deleteMetadataItem(String serverId, String key) {
        checkNotNull(serverId);
        checkNotNull(key);
        return ToActionResponseFunction.INSTANCE.apply(
                  delete(Void.class, uri("/servers/%s/metadata/%s", serverId, key)).executeWithResponse()
                );
    }

    private int sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return ms;
    }
}