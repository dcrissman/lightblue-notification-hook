package org.esbtools.lightbluenotificationhook;

import com.redhat.lightblue.DataError;
import com.redhat.lightblue.EntityVersion;
import com.redhat.lightblue.Response;
import com.redhat.lightblue.config.LightblueFactory;
import com.redhat.lightblue.config.LightblueFactoryAware;
import com.redhat.lightblue.crud.CRUDOperation;
import com.redhat.lightblue.crud.InsertionRequest;
import com.redhat.lightblue.eval.Projector;
import com.redhat.lightblue.hooks.CRUDHook;
import com.redhat.lightblue.hooks.HookDoc;
import com.redhat.lightblue.mediator.Mediator;
import com.redhat.lightblue.metadata.EntityMetadata;
import com.redhat.lightblue.metadata.Field;
import com.redhat.lightblue.metadata.HookConfiguration;
import com.redhat.lightblue.util.DocComparator;
import com.redhat.lightblue.util.Error;
import com.redhat.lightblue.util.JsonCompare;
import com.redhat.lightblue.util.JsonDoc;
import com.redhat.lightblue.util.JsonNodeCursor;
import com.redhat.lightblue.util.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ContainerNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NotificationHook implements CRUDHook, LightblueFactoryAware {
    
    private final String name;
    private final JsonNodeFactory jsonNodeFactory;
    private final ObjectMapper objectMapper;

    private @Nullable LightblueFactory lightblueFactory;
    private @Nullable Mediator mediator;

    private static final Logger LOGGER=LoggerFactory.getLogger(NotificationHook.class);

    public NotificationHook(String name) {
        this(name, null);
    }

    public NotificationHook(String name, Mediator mediator) {
        this(name, new ObjectMapper(), JsonNodeFactory.withExactBigDecimals(true), mediator);
    }

    public NotificationHook(String name, ObjectMapper objectMapper,
            JsonNodeFactory jsonNodeFactory, Mediator mediator) {
        this.name = name;
        this.jsonNodeFactory = jsonNodeFactory;
        this.objectMapper = objectMapper;
        this.mediator = mediator;
    }

    @Override
    public void setLightblueFactory(LightblueFactory lightblueFactory) {
        this.lightblueFactory = lightblueFactory;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void processHook(EntityMetadata entityMetadata,
                            HookConfiguration hookConfiguration,
                            List<HookDoc> hookDocs) {
        if (hookConfiguration == null) {
            LOGGER.warn("No notificationHook configuration provided, assuming you want to watch "
                    + "all fields and include only IDs.");
            hookConfiguration = NotificationHookConfiguration.watchingEverythingAndIncludingNothing();
        } else if (!(hookConfiguration instanceof NotificationHookConfiguration)) {
            throw new IllegalArgumentException("Expected instance of " +
                    "NotificationHookConfiguration but got: " + hookConfiguration);
        }

        NotificationHookConfiguration config = (NotificationHookConfiguration) hookConfiguration;
        Mediator mediator = tryGetMediator();

        Projector watchProjector = Projector.getInstance(config.watchProjection(), entityMetadata);
        Projector includeProjector = Projector.getInstance(config.includeProjection(), entityMetadata);

        for (HookDoc hookDoc : hookDocs) {
            HookResult result = processSingleHookDoc(entityMetadata,
                                                     watchProjector,
                                                     includeProjector,
                                                     config.isArrayOrderingSignificant(),
                                                     hookDoc,
                                                     mediator);
            
            // TODO: batch
            if(result.hasException()) {
                throw new NotificationProcessingError(result.exception);
            } else if (result.hasErrorsOrDataErrors()) {                
                throw new NotificationInsertErrorsException(result.entity, result.errors,
                                                            result.dataErrors);
            }
        }
    }
    
    private HookResult processSingleHookDoc(EntityMetadata metadata,
                                            Projector watchProjector,
                                            Projector includeProjector,
                                            boolean arrayOrderingSignificant,
                                            HookDoc hookDoc,
                                            Mediator mediator) {
        LOGGER.debug("Processing doc starts");
        JsonDoc postDoc = hookDoc.getPostDoc();
        JsonDoc preDoc = hookDoc.getPreDoc();

        // TODO(ahenning): Support delete
        if (hookDoc.getCRUDOperation().equals(CRUDOperation.FIND) || postDoc == null) {
            return HookResult.aborted();
        }

        try {
            DocComparator.Difference<JsonNode> diff=compareDocs(metadata,preDoc,postDoc,watchProjector);
            if(!diff.same()) {
                if(diff.getNumChangedFields()>0 || arrayOrderingSignificant) {                
                    LOGGER.debug("Watched fields changed, creating notification");
                    NotificationEntity notification =
                        makeNotificationEntityWithIncludedFields(hookDoc, includeProjector, diff, arrayOrderingSignificant);
                    
                    EntityVersion notificationVersion = new EntityVersion(NotificationEntity.ENTITY_NAME,
                                                                          NotificationEntity.ENTITY_VERSION);
                    
                    InsertionRequest newNotification = new InsertionRequest();
                    
                    newNotification.setEntityVersion(notificationVersion);
                    newNotification.setEntityData(objectMapper.valueToTree(notification));
                    
                    LOGGER.debug("Inserting notification");
                    Response response = mediator.insert(newNotification);
                    
                    return new HookResult(notification, response.getErrors(), response.getDataErrors());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error processing hook:"+e);
            return HookResult.exception(e);
        }
        return HookResult.aborted();
    }

    /**
     * Compares the pre- and post- documents after they are passed
     * through the watchProjector, and returns the delta
     */
    private DocComparator.Difference<JsonNode> compareDocs(EntityMetadata metadata,
                                                           JsonDoc preDoc,
                                                           JsonDoc postDoc,
                                                           Projector watchProjector)
        throws Exception {
        JsonDoc watchedPostDoc = watchProjector.project(postDoc, jsonNodeFactory);
        JsonDoc watchedPreDoc = preDoc == null
            ? new JsonDoc(jsonNodeFactory.objectNode())
            : watchProjector.project(preDoc, jsonNodeFactory);
        
        // Compute diff
        JsonCompare cmp=metadata.getDocComparator();
        LOGGER.debug("Array identities:{}",cmp.getArrayIdentities());
        LOGGER.debug("Pre:{}, Post:{}",watchedPreDoc.getRoot(),watchedPostDoc.getRoot());
        DocComparator.Difference<JsonNode> diff=cmp.
            compareNodes(watchedPreDoc.getRoot(),watchedPostDoc.getRoot());
        LOGGER.debug("Diff: {}",diff);
        return diff;
    }

    private NotificationEntity makeNotificationEntityWithIncludedFields(HookDoc hookDoc,
                                                                        Projector includeProjector,
                                                                        DocComparator.Difference<JsonNode> diff,
                                                                        boolean arrayOrderSignificant) {
        EntityMetadata metadata = hookDoc.getEntityMetadata();
        JsonDoc postDoc = hookDoc.getPostDoc();

        NotificationEntity notificationEntity = new NotificationEntity();
        // Set the payload
        JsonDoc includeDoc=includeProjector.project(postDoc,jsonNodeFactory);
        
        List<NotificationEntity.PathAndValue> data=new ArrayList<>();

        // Don't iterate if empty doc
        if(includeDoc.getRoot().size()>0) {
            JsonNodeCursor cursor=includeDoc.cursor();
            while(cursor.next()) {
                JsonNode node=cursor.getCurrentNode();
                if(!(node instanceof ContainerNode)) {
                    data.add(new NotificationEntity.PathAndValue(cursor.getCurrentPath().toString(),
                                                                 node.asText()));
                }
            }
        }
        // Add entity identities to properties
        for (Field identityField : metadata.getEntitySchema().getIdentityFields()) {
            Path identityPath = identityField.getFullPath();
            String pathString = identityPath.toString();
            boolean found=false;
            for(NotificationEntity.PathAndValue v:data) {
                if(v.getPath().equals(pathString)) {
                    found=true;
                    break;
                }
            }
            if(!found) {
                String valueString = postDoc.get(identityPath).asText();            
                data.add(new NotificationEntity.PathAndValue(pathString, valueString));
            }
        }
        List<String> changedPaths=new ArrayList<>();
        List<NotificationEntity.PathAndValue> removedPaths=new ArrayList<>();
        for(DocComparator.Delta<JsonNode> delta:diff.getDelta()) {
            if( (delta instanceof DocComparator.Move && arrayOrderSignificant) ||
                !(delta instanceof DocComparator.Move) ) {

                if(delta instanceof DocComparator.Removal) {
                    removedPaths.addAll(flatten(delta.getField().toString(),
                                                ((DocComparator.Removal<JsonNode>)delta).getRemovedNode()));
                } else {
                    if(delta instanceof DocComparator.Move) {
                        // Add the new path to the changedPaths
                        changedPaths.add(delta.getField2().toString());
                    } else {
                        changedPaths.add(delta.getField().toString());
                    }
                }
            }
        }
        notificationEntity.setChangedPaths(changedPaths);
        notificationEntity.setRemovedEntityData(removedPaths);
        notificationEntity.setEntityData(data);

        // TODO(ahenning): Support delete
        NotificationEntity.Operation operation = hookDoc.getPreDoc() == null
            ? NotificationEntity.Operation.insert
            : NotificationEntity.Operation.update;

        notificationEntity.setEntityName(metadata.getName());
        notificationEntity.setEntityVersion(metadata.getVersion().getValue());
        notificationEntity.setOperation(operation);
        notificationEntity.setTriggeredByUser(hookDoc.getWho());
        notificationEntity.setOccurrenceDate(hookDoc.getWhen());
        notificationEntity.setStatus(NotificationEntity.Status.unprocessed);
        
        return notificationEntity;
    }

    private List<NotificationEntity.PathAndValue> flatten(String prefix,JsonNode node) {
        List<NotificationEntity.PathAndValue> list=new ArrayList<>();
        JsonNodeCursor cursor=new JsonNodeCursor(Path.EMPTY,node);
        while(cursor.next()) {
            String p=cursor.getCurrentPath().toString();
            JsonNode value=cursor.getCurrentNode();
            if(value.isValueNode()) {
                list.add(new NotificationEntity.PathAndValue(prefix.isEmpty()?p:(prefix+"."+p),value.asText()));
            }
        }
        return list;
    }

    // TODO(ahenning): This messiness can be removed if we can inject the lightblue factory in
    // the parser instead of the hook. Then hook can accept mediator in constructor and we only
    // validate it is non null and that's it.
    // See: https://github.com/lightblue-platform/lightblue-core/pull/587
    protected Mediator tryGetMediator() {
        if (mediator != null) {
            return mediator;
        }

        synchronized (this) {
            if (mediator != null) {
                return mediator;
            }

            if (lightblueFactory == null) {
                throw new IllegalStateException("No Mediator or LightblueFactory provided!");
            }

            try {
                return mediator = lightblueFactory.getMediator();
            } catch (Exception e) {
                throw new IllegalStateException("Unable to get Mediator from LightblueFactory!", e);
            }
        }
    }

    static class HookResult {
        final NotificationEntity entity;
        final List<Error> errors;
        final List<DataError> dataErrors;
        final Exception exception;

        static HookResult aborted() {
            return new HookResult(null, Collections.<Error>emptyList(),
                                  Collections.<DataError>emptyList(),null);
        }

        static HookResult exception(Exception x) {
            return new HookResult(null, Collections.<Error>emptyList(),
                                  Collections.<DataError>emptyList(),x);
        }

        HookResult(NotificationEntity entity, List<Error> errors, List<DataError> dataErrors, Exception exception) {
            this.entity = entity;
            this.errors = errors;
            this.dataErrors = dataErrors;
            this.exception = exception;
        }

        HookResult(NotificationEntity entity, List<Error> errors, List<DataError> dataErrors) {
            this(entity,errors,dataErrors,null);
        }

        boolean hasErrorsOrDataErrors() {
            return !errors.isEmpty() || !dataErrors.isEmpty();
        }

        boolean hasException() {
            return exception!=null;
        }
    }
}
