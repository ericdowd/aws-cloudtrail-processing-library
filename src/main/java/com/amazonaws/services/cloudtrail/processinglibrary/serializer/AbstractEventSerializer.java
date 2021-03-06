/*******************************************************************************
 * Copyright 2010-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 ******************************************************************************/

package com.amazonaws.services.cloudtrail.processinglibrary.serializer;

import com.amazonaws.services.cloudtrail.processinglibrary.model.CloudTrailEvent;
import com.amazonaws.services.cloudtrail.processinglibrary.model.CloudTrailEventData;
import com.amazonaws.services.cloudtrail.processinglibrary.model.CloudTrailEventMetadata;
import com.amazonaws.services.cloudtrail.processinglibrary.model.internal.*;
import com.amazonaws.services.cloudtrail.processinglibrary.utils.LibraryUtils;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.text.ParseException;
import java.util.*;

/**
 * Abstract base class for Event Serializer implementations.
 */
public abstract class AbstractEventSerializer implements EventSerializer {

    private static final Log logger = LogFactory.getLog(AbstractEventSerializer.class);
    private static final String RECORDS = "Records";
    private static final double SUPPORTED_EVENT_VERSION = 1.06d;

    /**
     * A Jackson JSON Parser object.
     */
    private JsonParser jsonParser;

    /**
     * Construct an AbstractEventSerializer object
     *
     * @param jsonParser a Jackson
     * <a href="http://jackson.codehaus.org/1.4.0/javadoc/org/codehaus/jackson/JsonParser.html">JsonParser</a> object to
     *     use for interpreting JSON objects.
     * @throws IOException under no conditions.
     */
    public AbstractEventSerializer (JsonParser jsonParser) throws IOException {
        this.jsonParser = jsonParser;
    }

    /**
     * An abstract class that returns an
     * {@link com.amazonaws.services.cloudtrail.processinglibrary.model.CloudTrailEventMetadata} object.
     *
     * @param charStart the character count at which to begin reading event data.
     * @param charEnd the character count at which to stop reading event data.
     * @return the event metadata.
     */
    public abstract CloudTrailEventMetadata getMetadata(int charStart, int charEnd);

    /**
     * Read the header of an AWS CloudTrail log.
     *
     * @throws JsonParseException if the log could not be parsed.
     * @throws IOException if the log could not be opened or accessed.
     */
    protected void readArrayHeader() throws IOException {
        if (jsonParser.nextToken() != JsonToken.START_OBJECT) {
            throw new JsonParseException("Not a Json object", jsonParser.getCurrentLocation());
        }

        jsonParser.nextToken();
        if (!jsonParser.getText().equals(RECORDS)) {
            throw new JsonParseException("Not a CloudTrail log", jsonParser.getCurrentLocation());
        }

        if (jsonParser.nextToken() != JsonToken.START_ARRAY) {
            throw new JsonParseException("Not a CloudTrail log", jsonParser.getCurrentLocation());
        }
    }

    /**
     * Indicates whether the CloudTrail log has more events to read.
     *
     * @return <code>true</code> if the log contains more events; <code>false</code> otherwise.
     * @throws IOException if the log could not be opened or accessed.
     */
    public boolean hasNextEvent() throws IOException {
        /* In Fasterxml parser, hasNextEvent will consume next token. So do not call it multiple times. */
        JsonToken nextToken = jsonParser.nextToken();
        return nextToken == JsonToken.START_OBJECT || nextToken == JsonToken.START_ARRAY;
    }

    /**
     * Close the JSON parser object used to read the CloudTrail log.
     *
     * @throws IOException if the log could not be opened or accessed.
     */
    public void close() throws IOException {
        jsonParser.close();
    }

    /**
     * Get the next event from the CloudTrail log and parse it.
     *
     * @return a {@link CloudTrailEvent} that represents the
     *     parsed event.
     * @throws IOException if the event could not be parsed.
     */
    public CloudTrailEvent getNextEvent() throws IOException {
        CloudTrailEventData eventData = new CloudTrailEventData();
        String key;

         /* Get next CloudTrailEvent event from log file. When failed to parse a event,
         * IOException will be thrown. In this case, the charEnd index the place we
         * encountered parsing error. */

        // return the starting location of the current token; that is, position of the first character
        // from input that starts the current token
        int charStart = (int) jsonParser.getTokenLocation().getCharOffset();

        while(jsonParser.nextToken() != JsonToken.END_OBJECT) {
            key = jsonParser.getCurrentName();

            switch (key) {
            case "eventVersion":
                String eventVersion = jsonParser.nextTextValue();
                if (Double.parseDouble(eventVersion) > SUPPORTED_EVENT_VERSION) {
                    logger.debug(String.format("EventVersion %s is not supported by CloudTrail.", eventVersion));
                }
                eventData.add(key, eventVersion);
                break;
            case "userIdentity":
                this.parseUserIdentity(eventData);
                break;
            case "eventTime":
                eventData.add(CloudTrailEventField.eventTime.name(), convertToDate(jsonParser.nextTextValue()));
                break;
            case "eventID":
                eventData.add(key, convertToUUID(jsonParser.nextTextValue()));
                break;
            case "readOnly":
                this.parseReadOnly(eventData);
                break;
            case "resources":
                this.parseResources(eventData);
                break;
            case "managementEvent":
                this.parseManagementEvent(eventData);
                break;
            default:
                eventData.add(key, parseDefaultValue(key));
                break;
            }
        }
        this.setAccountId(eventData);

        // event's last character position in the log file.
        int charEnd = (int) jsonParser.getTokenLocation().getCharOffset();

        CloudTrailEventMetadata metaData = getMetadata(charStart, charEnd);

        return new CloudTrailEvent(eventData, metaData);
    }

    /**
     * Set AccountId in CloudTrailEventData top level from either recipientAccountID or from UserIdentity.
     * If recipientAccountID exists then recipientAccountID is set to accountID; otherwise, accountID is retrieved
     * from UserIdentity.
     *
     * There are 2 places accountID would appear in UserIdentity: first is the UserIdentity top level filed
     * and the second place is accountID inside SessionIssuer. If accountID exists in the top level field, then it is
     * set to accountID; otherwise, accountID is retrieved from SessionIssuer.
     *
     * If all 3 places cannot find accountID, then accountID is not set.
     *
     * @param eventData the event data to set.
     */
    private void setAccountId(CloudTrailEventData eventData) {
        if (eventData.getRecipientAccountId() != null) {
            eventData.add("accountId", eventData.getRecipientAccountId());
            return;
        }

        if (eventData.getUserIdentity() != null &&
            eventData.getUserIdentity().getAccountId() != null) {
            eventData.add("accountId", eventData.getUserIdentity().getAccountId());
            return;
        }

        if (eventData.getUserIdentity() != null &&
            eventData.getUserIdentity().getAccountId() == null &&
            eventData.getUserIdentity().getSessionContext() != null &&
            eventData.getUserIdentity().getSessionContext().getSessionIssuer() != null &&
            eventData.getUserIdentity().getSessionContext().getSessionIssuer().getAccountId() != null) {
            eventData.add("accountId", eventData.getUserIdentity().getSessionContext().getSessionIssuer().getAccountId());
        }
    }

    /**
     * Parses the {@link UserIdentity} in CloudTrailEventData
     *
     * @param eventData {@link CloudTrailEventData} needs to parse.
     * @throws IOException
     */
    private void parseUserIdentity(CloudTrailEventData eventData) throws IOException {
        JsonToken nextToken = jsonParser.nextToken();
        if (nextToken == JsonToken.VALUE_NULL) {
            eventData.add(CloudTrailEventField.userIdentity.name(), null);
            return;
        }

        if (nextToken != JsonToken.START_OBJECT) {
            throw new JsonParseException("Not a UserIdentity object", jsonParser.getCurrentLocation());
        }

        UserIdentity userIdentity = new UserIdentity();

        while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
            String key = jsonParser.getCurrentName();

            switch (key) {
            case "type":
                userIdentity.add(CloudTrailEventField.type.name(), jsonParser.nextTextValue());
                break;
            case "principalId":
                userIdentity.add(CloudTrailEventField.principalId.name(), jsonParser.nextTextValue());
                break;
            case "arn":
                userIdentity.add(CloudTrailEventField.arn.name(), jsonParser.nextTextValue());
                break;
            case "accountId":
                userIdentity.add(CloudTrailEventField.accountId.name(), jsonParser.nextTextValue());
                break;
            case "accessKeyId":
                userIdentity.add(CloudTrailEventField.accessKeyId.name(), jsonParser.nextTextValue());
                break;
            case "userName":
                userIdentity.add(CloudTrailEventField.userName.name(), jsonParser.nextTextValue());
                break;
            case "sessionContext":
                this.parseSessionContext(userIdentity);
                break;
            case "invokedBy":
                userIdentity.add(CloudTrailEventField.invokedBy.name(), jsonParser.nextTextValue());
                break;
            case "identityProvider":
                userIdentity.add(CloudTrailEventField.identityProvider.name(), jsonParser.nextTextValue());
                break;
            default:
                userIdentity.add(key, parseDefaultValue(key));
                break;
            }
        }
        eventData.add(CloudTrailEventField.userIdentity.name(), userIdentity);
    }

    /**
     * Parses the {@link SessionContext} object.
     *
     * @param userIdentity the {@link com.amazonaws.services.cloudtrail.processinglibrary.model.internal.UserIdentity}
     * @throws IOException
     * @throws JsonParseException
     */
    private void parseSessionContext(UserIdentity userIdentity) throws IOException {
        if (jsonParser.nextToken() != JsonToken.START_OBJECT) {
            throw new JsonParseException("Not a SessionContext object", jsonParser.getCurrentLocation());
        }

        SessionContext sessionContext = new SessionContext();

        while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
            String key = jsonParser.getCurrentName();

            switch (key) {
            case "attributes":
                sessionContext.add(CloudTrailEventField.attributes.name(), parseAttributes());
                break;
            case "sessionIssuer":
                sessionContext.add(CloudTrailEventField.sessionIssuer.name(), parseSessionIssuer(sessionContext));
                break;
            case "webIdFederationData":
                sessionContext.add(CloudTrailEventField.webIdFederationData.name(), parseWebIdentitySessionContext(sessionContext));
                break;
            default:
                sessionContext.add(key, parseDefaultValue(key));
                break;
            }
        }

        userIdentity.add(CloudTrailEventField.sessionContext.name(), sessionContext);

    }

    /**
     * Parses the {@link WebIdentitySessionContext} object.
     *
     * @param sessionContext {@link SessionContext}
     * @return the web identity session context
     * @throws IOException
     */
    private WebIdentitySessionContext parseWebIdentitySessionContext(SessionContext sessionContext) throws IOException {
        if (jsonParser.nextToken() != JsonToken.START_OBJECT) {
            throw new JsonParseException("Not a WebIdentitySessionContext object", jsonParser.getCurrentLocation());
        }

        WebIdentitySessionContext webIdFederationData = new WebIdentitySessionContext();

        while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
            String key = jsonParser.getCurrentName();

            switch (key) {
            case "attributes":
                webIdFederationData.add(CloudTrailEventField.attributes.name(), parseAttributes());
                break;
            case "federatedProvider":
                webIdFederationData.add(CloudTrailEventField.federatedProvider.name(), jsonParser.nextTextValue());
                break;
            default:
                webIdFederationData.add(key, parseDefaultValue(key));
                break;
            }
        }

        return webIdFederationData;
    }


    /**
     * Parses the {@link SessionContext} object.
     * This runs only if the session is running with role-based or federated access permissions
     * (in other words, temporary credentials in IAM).
     *
     * @param sessionContext
     * @return the session issuer object.
     * @throws IOException
     */
    private SessionIssuer parseSessionIssuer(SessionContext sessionContext) throws IOException {
        if (jsonParser.nextToken() != JsonToken.START_OBJECT) {
            throw new JsonParseException("Not a SessionIssuer object", jsonParser.getCurrentLocation());
        }

        SessionIssuer sessionIssuer = new SessionIssuer();

        while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
            String key = jsonParser.getCurrentName();

            switch (key) {
            case "type":
                sessionIssuer.add(CloudTrailEventField.type.name(), this.jsonParser.nextTextValue());
                break;
            case "principalId":
                sessionIssuer.add(CloudTrailEventField.principalId.name(), this.jsonParser.nextTextValue());
                break;
            case "arn":
                sessionIssuer.add(CloudTrailEventField.arn.name(), this.jsonParser.nextTextValue());
                break;
            case "accountId":
                sessionIssuer.add(CloudTrailEventField.accountId.name(), this.jsonParser.nextTextValue());
                break;
            case "userName":
                sessionIssuer.add(CloudTrailEventField.userName.name(), this.jsonParser.nextTextValue());
                break;
            default:
                sessionIssuer.add(key, this.parseDefaultValue(key));
                break;
            }
        }

        return sessionIssuer;
    }

    /**
     * Parses the event readOnly attribute.
     *
     * @param eventData
     *
     * @throws JsonParseException
     * @throws IOException
     */
    private void parseReadOnly(CloudTrailEventData eventData) throws IOException {
        jsonParser.nextToken();
        Boolean readOnly = null;
        if (jsonParser.getCurrentToken() != JsonToken.VALUE_NULL) {
            readOnly = jsonParser.getBooleanValue();
        }
        eventData.add(CloudTrailEventField.readOnly.name(), readOnly);
    }

    /**
     * Parses the event managementEvent attribute.
     * @param eventData the interesting {@link CloudTrailEventData}
     * @throws IOException
     */
    private void parseManagementEvent(CloudTrailEventData eventData) throws IOException {
        jsonParser.nextToken();
        Boolean managementEvent = null;
        if (jsonParser.getCurrentToken() != JsonToken.VALUE_NULL) {
            managementEvent = jsonParser.getBooleanValue();
        }
        eventData.add(CloudTrailEventField.managementEvent.name(), managementEvent);
    }

    /**
     * Parses a list of Resource.
     *
     * @param eventData the resources belong to
     * @throws IOException
     */
    private void parseResources(CloudTrailEventData eventData) throws IOException {
        JsonToken nextToken = jsonParser.nextToken();
        if (nextToken == JsonToken.VALUE_NULL) {
            eventData.add(CloudTrailEventField.resources.name(), null);
            return;
        }

        if (nextToken != JsonToken.START_ARRAY) {
            throw new JsonParseException("Not a list of resources object", jsonParser.getCurrentLocation());
        }

        List<Resource> resources = new ArrayList<Resource>();

        while (jsonParser.nextToken() != JsonToken.END_ARRAY) {
            resources.add(parseResource());
        }

        eventData.add(CloudTrailEventField.resources.name(), resources);
    }

    /**
     * Parses a single Resource.
     *
     * @return a single resource
     * @throws IOException
     */
    private Resource parseResource() throws IOException {
        //current token is ready consumed by parseResources
        if (jsonParser.getCurrentToken() != JsonToken.START_OBJECT) {
            throw new JsonParseException("Not a Resource object", jsonParser.getCurrentLocation());
        }

        Resource resource = new Resource();

        while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
            String key = jsonParser.getCurrentName();

            switch (key) {
            default:
                resource.add(key, parseDefaultValue(key));
                break;
            }
        }

        return resource;
    }

    /**
     * Parses the event with key as default value.
     *
     * If the value is JSON null, then we will return null.
     * If the value is JSON object (of starting with START_ARRAY or START_OBject) , then we will convert the object to String.
     * If the value is JSON scalar value (non-structured object), then we will return simply return it as String.
     *
     * @param key
     * @throws IOException
     */
    private String parseDefaultValue(String key) throws IOException {
        jsonParser.nextToken();
        String value = null;
        JsonToken currentToken = jsonParser.getCurrentToken();
        if (currentToken != JsonToken.VALUE_NULL) {
            if (currentToken == JsonToken.START_ARRAY || currentToken == JsonToken.START_OBJECT) {
                JsonNode node = jsonParser.readValueAsTree();
                value = node.toString();
            } else {
                value = jsonParser.getValueAsString();
            }
        }
        return value;
    }

    /**
     * Parses attributes as a Map, used in both parseWebIdentitySessionContext and parseSessionContext
     *
     * @return attributes for either session context or web identity session context
     * @throws IOException
     */
    private Map<String, String> parseAttributes() throws IOException {
        if (jsonParser.nextToken() != JsonToken.START_OBJECT) {
            throw new JsonParseException("Not a Attributes object", jsonParser.getCurrentLocation());
        }

        Map<String, String> attributes = new HashMap<>();

        while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
            String key = jsonParser.getCurrentName();
            String value = jsonParser.nextTextValue();
            attributes.put(key, value);
        }

        return attributes;
    }

    /**
     * This method convert a String to UUID type. Currently EventID is in UUID type.
     *
     * @param str that need to convert to UUID
     * @return the UUID.
     */
    private UUID convertToUUID(String str) {
        return UUID.fromString(str);
    }

    /**
     * This method convert a String to Date type. When parse error happened return current date.
     *
     * @param dateInString the String to convert to Date
     * @return Date the date and time in coordinated universal time
     * @throws IOException
     */
    private Date convertToDate(String dateInString) throws IOException {
        Date date = null;
        if (dateInString != null) {
            try {
                date = LibraryUtils.getUtcSdf().parse(dateInString);
            } catch (ParseException e) {
                throw new IOException("Cannot parse " + dateInString + " as Date", e);
            }
        }
        return date;
    }
}
