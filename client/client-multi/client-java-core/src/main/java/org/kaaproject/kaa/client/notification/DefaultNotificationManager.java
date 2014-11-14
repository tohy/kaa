/*
 * Copyright 2014 CyberVision, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kaaproject.kaa.client.notification;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.kaaproject.kaa.client.channel.NotificationTransport;
import org.kaaproject.kaa.client.persistence.KaaClientState;
import org.kaaproject.kaa.common.endpoint.gen.Notification;
import org.kaaproject.kaa.common.endpoint.gen.SubscriptionCommand;
import org.kaaproject.kaa.common.endpoint.gen.SubscriptionCommandType;
import org.kaaproject.kaa.common.endpoint.gen.SubscriptionType;
import org.kaaproject.kaa.common.endpoint.gen.Topic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link NotificationManager} implementation.
 *
 * @author Yaroslav Zeygerman
 *
 */
public class DefaultNotificationManager implements NotificationManager, NotificationProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultNotificationManager.class);

    private Map<String, Topic> topics = new HashMap<String, Topic>();

    private final Set<NotificationListener> mandatoryListeners = new HashSet<NotificationListener>();
    private final Map<String, List<NotificationListener>> voluntaryListeners = new HashMap<String, List<NotificationListener>>();
    private final Set<NotificationTopicListListener> topicsListeners = new HashSet<NotificationTopicListListener>();

    private final List<SubscriptionCommand> subscriptionInfo = new LinkedList<SubscriptionCommand>();

    private final KaaClientState state;

    private volatile NotificationTransport transport;

    public DefaultNotificationManager(KaaClientState state, NotificationTransport transport) {
        this.state = state;
        this.transport = transport;

        List<Topic> topicList = state.getTopics();

        if (topicList != null) {
            for (Topic topic : topicList) {
                topics.put(topic.getId(), topic);
            }
        }
    }

    @Override
    public void addNotificationListener(NotificationListener listener) {
        if (listener == null) {
            LOG.warn("Failed to add notification listener: null");
            throw new IllegalArgumentException("NUll notification listener");
        }

        synchronized (mandatoryListeners) {
            if (!mandatoryListeners.contains(listener)) {
                mandatoryListeners.add(listener);
            }
        }
    }

    @Override
    public void removeNotificationListener(NotificationListener listener) {
        if (listener == null) {
            LOG.warn("Failed to remove notification listener: null");
            throw new IllegalArgumentException("NUll notification listener");
        }

        synchronized (mandatoryListeners) {
            mandatoryListeners.remove(listener);
        }
    }

    @Deprecated
    @Override
    public void updateTopicSubscriptions(Map<String, List<NotificationListenerInfo>> subscribers)
            throws UnavailableTopicException
    {
        if (subscribers == null) {
            LOG.warn("Failed to update topic subsciptions: null subscribers");
            throw new IllegalArgumentException("NUll subscribers");
        }

        List<SubscriptionCommand> subscriptionUpdate = new LinkedList<>();

        for (Map.Entry<String, List<NotificationListenerInfo>> cursor : subscribers.entrySet()) {
            Topic listenerTopic = findTopicById(cursor.getKey());

            synchronized (voluntaryListeners) {
                List<NotificationListener> listeners = voluntaryListeners.get(cursor.getKey());

                if (cursor.getValue() != null) {
                    for (NotificationListenerInfo subscriberInfo : cursor.getValue()) {
                        if (subscriberInfo.getAction() == NotificationListenerInfo.Action.ADD) {
                            if (listeners == null) {
                                listeners = new LinkedList<NotificationListener>();
                                voluntaryListeners.put(listenerTopic.getId(), listeners);
                            }

                            if (listeners.isEmpty() && listenerTopic.getSubscriptionType() == SubscriptionType.VOLUNTARY) {
                                subscriptionUpdate.add(new SubscriptionCommand(
                                        listenerTopic.getId(), SubscriptionCommandType.ADD));
                            }

                            if (subscriberInfo.getListener() != null && !listeners.contains(subscriberInfo.getListener())) {
                                listeners.add(subscriberInfo.getListener());
                            }
                        } else if (listeners != null) {
                            listeners.remove(subscriberInfo.getListener());

                            if (listeners.isEmpty() && listenerTopic.getSubscriptionType() == SubscriptionType.VOLUNTARY) {
                                subscriptionUpdate.add(new SubscriptionCommand(
                                        listenerTopic.getId(), SubscriptionCommandType.REMOVE));
                            }
                        }
                    }
                }
            }
        }

        if (!subscriptionUpdate.isEmpty()) {
            updateSubscriptionInfo(subscriptionUpdate);
        }

        if (transport != null) {
            transport.sync();
        }
    }

    @Deprecated
    @Override
    public void addMandatoryTopicsListener(NotificationListener listener) {
        addNotificationListener(listener);
    }

    @Deprecated
    @Override
    public void removeMandatoryTopicsListener(NotificationListener listener) {
        removeNotificationListener(listener);
    }

    @Override
    public void addTopicListListener(NotificationTopicListListener listener) {
        if (listener == null) {
            LOG.warn("Failed to add topic list listener: null");
            throw new IllegalArgumentException("NUll topic list listener");
        }

        synchronized (topicsListeners) {
            topicsListeners.add(listener);
        }
    }

    @Override
    public void removeTopicListListener(NotificationTopicListListener listener) {
        if (listener == null) {
            LOG.warn("Failed to remove topic list listener: null");
            throw new IllegalArgumentException("NUll topic list listener");
        }

        synchronized (topicsListeners) {
            topicsListeners.remove(listener);
        }
    }

    @Override
    public List<Topic> getTopics() {
        List<Topic> topicList = new LinkedList<Topic>();

        synchronized (topics) {
            for (Topic topic : topics.values()) {
                topicList.add(topic);
            }
        }

        return topicList;
    }

    @Override
    public void subscribeToTopic(String topicId, boolean forceSync)
            throws UnavailableTopicException
    {
        Topic topic = findTopicById(topicId);
        if (topic.getSubscriptionType() != SubscriptionType.VOLUNTARY) {
            LOG.warn("Failed to subscribe: topic '{}' isn't voluntary", topicId);
            throw new UnavailableTopicException(
                    String.format("Topic '%s' isn't voluntary", topicId));
        }

        updateSubscriptionInfo(topicId, SubscriptionCommandType.ADD);

        if (forceSync) {
            doSync();
        }
    }

    @Override
    public void subscribeToTopics(List<String> topicIds, boolean forceSync)
            throws UnavailableTopicException
    {
        List<SubscriptionCommand> subscriptionUpdate = new LinkedList<>();

        for (String id : topicIds) {
            Topic topic = findTopicById(id);
            if (topic.getSubscriptionType() != SubscriptionType.VOLUNTARY) {
                LOG.warn("Failed to subscribe: topic '{}' isn't voluntary", id);
                throw new UnavailableTopicException(
                        String.format("Topic '%s' isn't voluntary", id));
            }

            subscriptionUpdate.add(new SubscriptionCommand(
                    id, SubscriptionCommandType.ADD));
        }

        updateSubscriptionInfo(subscriptionUpdate);

        if (forceSync) {
            doSync();
        }
    }

    @Override
    public void unsubscribeFromTopic(String topicId, boolean forceSync)
            throws UnavailableTopicException
    {
        Topic topic = findTopicById(topicId);
        if (topic.getSubscriptionType() != SubscriptionType.VOLUNTARY) {
            LOG.warn("Failed to unsubscribe: topic '{}' isn't voluntary", topicId);
            throw new UnavailableTopicException(
                    String.format("Topic '%s' isn't voluntary", topicId));
        }

        topicsListeners.remove(topicId);
        updateSubscriptionInfo(topicId, SubscriptionCommandType.REMOVE);

        if (forceSync) {
            doSync();
        }
    }

    @Override
    public void unsubscribeFromTopics(List<String> topicIds, boolean forceSync) throws UnavailableTopicException {
        List<SubscriptionCommand> subscriptionUpdate = new LinkedList<>();

        for (String id : topicIds) {
            Topic topic = findTopicById(id);
            if (topic.getSubscriptionType() != SubscriptionType.VOLUNTARY) {
                LOG.warn("Failed to unsubscribe: topic '{}' isn't voluntary", id);
                throw new UnavailableTopicException(
                        String.format("Topic '%s' isn't voluntary", id));
            }

            topicsListeners.remove(id);
            subscriptionUpdate.add(new SubscriptionCommand(
                    id, SubscriptionCommandType.REMOVE));
        }

        updateSubscriptionInfo(subscriptionUpdate);

        if (forceSync) {
            doSync();
        }
    }

    @Override
    public void addNotificationListener(String topicId, NotificationListener listener)
            throws UnavailableTopicException
    {
        if (topicId == null || listener == null) {
            LOG.warn("Failed to add listener: id={}, listener={}", topicId, listener);
            throw new IllegalArgumentException("Bad listener data");
        }

        findTopicById(topicId);

        synchronized (voluntaryListeners) {
            List<NotificationListener> listeners = voluntaryListeners.get(topicId);

            if (listeners == null) {
                listeners = new LinkedList<>();
                voluntaryListeners.put(topicId, listeners);
            }

            listeners.add(listener);
        }
    }

    @Override
    public void removeNotificationListener(String topicId, NotificationListener listener)
            throws UnavailableTopicException
    {
        if (topicId == null || listener == null) {
            LOG.warn("Failed to remove listener: id={}, listener={}", topicId, listener);
            throw new IllegalArgumentException("Bad listener data");
        }

        findTopicById(topicId);

        synchronized (voluntaryListeners) {
            List<NotificationListener> listeners = voluntaryListeners.get(topicId);

            if (listeners != null) {
                listeners.remove(listener);
            }
        }
    }

    @Override
    public void sync() {
        doSync();
    }

    @Override
    public void topicsListUpdated(List<Topic> list) {
        Map<String, Topic> newTopics = new HashMap<String, Topic>();

        synchronized (topics) {
            for (Topic topic : list) {
                newTopics.put(topic.getId(), topic);
                if (topics.remove(topic.getId()) == null) {
                    state.addTopic(topic);
                }
            }
            synchronized (voluntaryListeners) {
                for (Topic topic : topics.values()) {
                    voluntaryListeners.remove(topic.getId());
                    state.removeTopic(topic.getId());
                }
            }
            topics = newTopics;
        }

        synchronized (topicsListeners) {
            for (NotificationTopicListListener listener : topicsListeners) {
                listener.onListUpdated(list);
            }
        }
    }

    @Override
    public void notificationReceived(List<Notification> notifications) throws IOException {
        for (Notification notification : notifications) {
            try {
                Topic topic = findTopicById(notification.getTopicId());
                boolean hasOwner = false;

                synchronized (voluntaryListeners) {
                    List<NotificationListener> listeners = voluntaryListeners.get(topic.getId());
                    if (listeners != null && !listeners.isEmpty()) {
                        hasOwner = true;
                        for (NotificationListener listener : listeners) {
                            listener.onNotificationRaw(topic.getId(), notification.getBody());
                        }
                    }
                }

                if (!hasOwner) {
                    synchronized (mandatoryListeners) {
                        for (NotificationListener listener : mandatoryListeners) {
                            listener.onNotificationRaw(topic.getId(), notification.getBody());
                        }
                    }
                }
            } catch (UnavailableTopicException e) {
                LOG.warn("Received notification for an unknown topic (id={})"
                                                    , notification.getTopicId());
            }
        }
    }

    private void updateSubscriptionInfo(String id, SubscriptionCommandType type) {
        synchronized (subscriptionInfo) {
            subscriptionInfo.add(new SubscriptionCommand(id, type));
        }
    }

    private void updateSubscriptionInfo(List<SubscriptionCommand> subscriptionUpdate) {
        synchronized (subscriptionInfo) {
            subscriptionInfo.addAll(subscriptionUpdate);
        }
    }

    private Topic findTopicById(String id) throws UnavailableTopicException {
        synchronized (topics) {
            Topic topic = topics.get(id);
            if (topic == null) {
                LOG.warn("Failed to find topic: id {} is unknown", id);
                throw new UnavailableTopicException(
                        String.format("Topic id '%s' is unknown", id));
            }

            return topic;
        }
    }

    private void doSync() {
        synchronized (subscriptionInfo) {
            transport.onSubscriptionChanged(subscriptionInfo);
            subscriptionInfo.clear();
            transport.sync();
        }
    }
}
