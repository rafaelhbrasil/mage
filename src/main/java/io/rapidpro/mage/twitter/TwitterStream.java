package io.rapidpro.mage.twitter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.rapidpro.mage.core.ChannelConfigException;
import io.rapidpro.mage.core.ChannelContext;
import io.rapidpro.mage.core.ChannelType;
import io.rapidpro.mage.core.ContactContext;
import io.rapidpro.mage.core.ContactUrn;
import io.rapidpro.mage.core.Direction;
import io.rapidpro.mage.core.IncomingContext;
import io.rapidpro.mage.service.MessageService;
import io.rapidpro.mage.temba.TembaRequest;
import com.twitter.hbc.core.StatsReporter;
import io.dropwizard.lifecycle.Managed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import twitter4j.DirectMessage;
import twitter4j.PagableResponseList;
import twitter4j.Paging;
import twitter4j.RateLimitStatus;
import twitter4j.ResponseList;
import twitter4j.TwitterException;
import twitter4j.User;
import twitter4j.UserStreamAdapter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Provides connection to a single Twitter account using both the Streaming API for real time message fetching, and the
 * REST API for back filling. When the stream is started it first checks the channel BOD field to see if there is a
 * last message id. If there isn't then we know that this is a new stream and we don't try to back-fill anything. If
 * there is then that is used as a starting point for back-filling.
 */
public class TwitterStream extends UserStreamAdapter implements Managed {

    protected static final Logger log = LoggerFactory.getLogger(TwitterStream.class);

    protected static final String CONFIG_HANDLE_ID = "handle_id";
    protected static final String CONFIG_TOKEN = "oauth_token";
    protected static final String CONFIG_TOKEN_SECRET = "oauth_token_secret";
    protected static final String CONFIG_AUTO_FOLLOW = "auto_follow";

    protected static final long BACKFILL_MAX_AGE = 60 * 60 * 1000; // 1 hour (in millis)

    private final TwitterManager m_manager;
    private final ChannelContext m_channel;
    private long m_handleId;
    private boolean m_autoFollow;

    private TwitterClients.RestClient m_restClient;
    private TwitterClients.StreamingClient m_streamingClient;
    private boolean m_backfillComplete = false;

    public TwitterStream(TwitterManager manager, ChannelContext channel, String apiKey, String apiSecret) throws ChannelConfigException {
        this.m_manager = manager;
        this.m_channel = channel;

        ObjectNode config = channel.getChannelConfig();
        if (config == null) {
            throw new ChannelConfigException("Channel #" + channel.getChannelId() + " has no configuration");
        }

        JsonNode handleId = config.get(CONFIG_HANDLE_ID);
        m_handleId = handleId != null ? handleId.longValue() : 0l;

        JsonNode token = config.get(CONFIG_TOKEN);
        JsonNode secret = config.get(CONFIG_TOKEN_SECRET);
        if (token == null || secret == null) {
            throw new ChannelConfigException("Channel #" + channel.getChannelId() + " has no Twitter auth configuration");
        }

        updateFromConfig(config);

        boolean production = m_manager.getServices().isProduction();

        m_restClient = TwitterClients.getRestClient(apiKey, apiSecret, token.textValue(), secret.textValue(), production);
        m_streamingClient = TwitterClients.getStreamingClient(apiKey, apiSecret, token.textValue(), secret.textValue(), production);
    }

    /**
     * Updates this stream according to the given channel config
     * @param config the configuration JSON
     */
    public void updateFromConfig(ObjectNode config) {
        JsonNode autoFollow = config.get(CONFIG_AUTO_FOLLOW);
        m_autoFollow = autoFollow == null || autoFollow.booleanValue();
    }

    /**
     * @see io.dropwizard.lifecycle.Managed#start()
     */
    @Override
    public void start() throws Exception {
        Long lastFollowerId = getLastFollowerId();

        m_manager.requestBackfill(new BackfillFetcherTask(lastFollowerId));
    }

    /**
     * @see io.dropwizard.lifecycle.Managed#stop()
     */
    @Override
    public void stop() throws Exception {
        m_streamingClient.stop();
    }

    /**
     * Called when back-filling step is complete or skipped
     */
    public void onBackfillComplete() {
        log.info("Finished back-fill task for channel #" + getChannel().getChannelId());

        m_backfillComplete = true;

        // to preserve message order, only start streaming after back-filling is complete
        m_streamingClient.start(this);
    }

    /**
     * Handles an incoming direct message, whether received via streaming or back-filling
     * @param message the direct message
     * @param fromStream whether this came from streaming or back-filling
     * @return the saved message id
     */
    protected int handleMessageReceived(DirectMessage message, boolean fromStream) {
        IncomingContext context = new IncomingContext(m_channel.getChannelId(), null, ChannelType.TWITTER, m_channel.getOrgId(), null);
        MessageService service = m_manager.getServices().getMessageService();
        ContactUrn from = new ContactUrn(ContactUrn.Scheme.TWITTER, message.getSenderScreenName());
        String name = message.getSenderScreenName();

        int savedId = service.createIncoming(context, from, message.getText(), message.getCreatedAt(), String.valueOf(message.getId()), name);

        log.info("Direct message " + message.getId() + " " + (fromStream ? "streamed" : "back-filled") + " on channel #" + m_channel.getChannelId() + " and saved as msg #" + savedId);

        return savedId;
    }

    /**
     * Handles a following of this handle, whether received via streaming or back-filling
     * @param follower the new follower
     * @param fromStream whether this came from streaming or back-filling
     */
    protected void handleNewFollower(User follower, boolean fromStream) {
        // ensure contact exists for this new follower
        ContactUrn urn = new ContactUrn(ContactUrn.Scheme.TWITTER, follower.getScreenName());
        ContactContext contact = m_manager.getServices().getContactService().getOrCreateContact(getChannel().getOrgId(),
                urn, getChannel().getChannelId(), follower.getScreenName());

        if (contact.isNewContact()) {
            log.info("New follower '" + follower.getScreenName() + "' " + (fromStream ? "streamed" : "back-filled") + " on channel #" + m_channel.getChannelId() + " and saved as contact #" + contact.getContactId());
        }

        // optionally follow back
        if (m_autoFollow) {
            try {
                m_restClient.createFriendship(follower.getId());

                log.debug("Auto-followed user '" + follower.getScreenName() + "' on channel # " + m_channel.getChannelId());
            } catch (TwitterException ex) {
                log.error("Unable to auto-follow '" + follower.getScreenName() + "' on channel # " + m_channel.getChannelId(), ex);
            }
        }

        // queue a request to notify Temba that the channel account has been followed
        TembaRequest request = TembaRequest.newFollowNotification(getChannel().getChannelId(), contact.getContactUrnId(), contact.isNewContact());
        m_manager.getTemba().queueRequest(request);

        setLastFollowerId(follower.getId());
    }

    /**
     * @see UserStreamAdapter#onDirectMessage(twitter4j.DirectMessage)
     */
    @Override
    public void onDirectMessage(DirectMessage message) {
        try {
            // don't do anything if we are the sender
            if (message.getSenderId() == m_handleId) {
                return;
            }

            handleMessageReceived(message, true);
        }
        catch (Exception ex) {
            // ensure any errors go to Sentry
            log.error("Unable to handle message", ex);
        }
    }

    /**
     * @see UserStreamAdapter#onFollow(twitter4j.User, twitter4j.User)
     */
    @Override
    public void onFollow(User follower, User followed) {
        try {
            // don't do anything the user being followed isn't us
            if (followed.getId() != m_handleId) {
                return;
            }

            handleNewFollower(follower, true);
        }
        catch (Exception ex) {
            // ensure any errors go to Sentry
            log.error("Unable to handle message", ex);
        }
    }

    /**
     * Background task which fetches potentially missed tweets and follows using the REST API. This happens in a task
     * so the Twitter Manager can run multiple back-fill tasks sequentially, making it easier to respect the Twitter API
     * rate limits.
     */
    protected class BackfillFetcherTask implements Runnable {

        private Long m_lastFollowerId;

        public BackfillFetcherTask(Long lastFollowerId) {
            this.m_lastFollowerId = lastFollowerId;
        }

        /**
         * @see Runnable#run()
         */
        @Override
        public void run() {
            log.info("Starting back-fill task for channel #" + getChannel().getChannelId() + " (last_follower=" + m_lastFollowerId + ")");

            if (m_lastFollowerId != null) {
                backfillFollowers();
            }
            else {
                // this is a new channel so don't back-fill followers but we do grab the last follower from their list
                // so we can use its id as a marker when back-filling next time
                User lastFollower = null;
                try {
                    PagableResponseList<User> followers = m_restClient.getFollowers(-1l);
                    if (followers != null) {
                        Iterator<User> users = followers.iterator();
                        lastFollower = users.hasNext() ? users.next() : null;
                    }
                }
                catch (TwitterException ex) {
                }
                setLastFollowerId(lastFollower != null ? lastFollower.getId() : 0);
            }

            backfillMessages();

            onBackfillComplete();
        }

        /**
         * Back-fills missed follows
         */
        protected void backfillFollowers() {
            long cursor = -1l;
            List<User> newFollowers = new ArrayList<>();

            outer:
            while (true) {
                try {
                    // this code assumes that followers are returned on order of last-to-follow first. According to
                    // Twitter API docs this may change in the future.
                    PagableResponseList<User> followers = m_restClient.getFollowers(cursor);
                    if (followers == null) {
                        break;
                    }

                    for (User follower : followers) {
                        if (follower.getId() == m_lastFollowerId) {
                            break outer;
                        }

                        newFollowers.add(follower);
                    }

                    if (followers.hasNext()) {
                        cursor = followers.getNextCursor();
                    } else {
                        break;
                    }

                } catch (TwitterException ex) {
                    if (ex.exceededRateLimitation()) {
                        // log as error so goes to Sentry
                        log.error("Exceeded rate limit", ex);

                        RateLimitStatus status = ex.getRateLimitStatus();
                        try {
                            Thread.sleep(status.getSecondsUntilReset() * 1000);
                            continue;

                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    break;
                }
            }

            // handle all follows in chronological order
            Collections.reverse(newFollowers);
            for (User follower : newFollowers) {
                handleNewFollower(follower, false);
            }
        }

        /**
         * Back-fills missed direct messages
         */
        protected void backfillMessages() {
            Long lastMessageId = getLastTwitterMessageId();
            Instant now = Instant.now();

            int page = 1;
            Paging paging = new Paging(page, 200);
            if (lastMessageId != null) {
                paging.setSinceId(lastMessageId);
            }

            List<DirectMessage> all_messages = new ArrayList<>();

            // fetch all messages - Twitter will give us them in reverse chronological order
            outer:
            while (true) {
                try {
                    ResponseList<DirectMessage> messages = m_restClient.getDirectMessages(paging);
                    if (messages == null) {
                        break;
                    }

                    long minPageMessageId = 0;

                    for (DirectMessage message : messages) {
                        // check if message is too old (thus all subsequent messages are too old)
                        if (Duration.between(message.getCreatedAt().toInstant(), now).toMillis() > BACKFILL_MAX_AGE) {
                            break outer;
                        }

                        all_messages.add(message);

                        minPageMessageId = Math.min(minPageMessageId, message.getId());
                    }

                    if (messages.size() < paging.getCount()) { // no more messages
                        break;
                    }

                    // update paging to get next 200 DMs, ensuring that we don't take new ones into account
                    paging.setPage(page);
                    paging.setMaxId(minPageMessageId - 1); // see https://dev.twitter.com/rest/public/timelines

                } catch (TwitterException ex) {
                    if (ex.exceededRateLimitation()) {
                        // log as error so goes to Sentry
                        log.error("Exceeded rate limit", ex);

                        RateLimitStatus status = ex.getRateLimitStatus();
                        try {
                            Thread.sleep(status.getSecondsUntilReset() * 1000);
                            continue;

                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    break;
                }
            }

            // handle all messages in chronological order
            Collections.reverse(all_messages);
            for (DirectMessage message : all_messages) {
                handleMessageReceived(message, false);
            }
        }

        protected Long getLastTwitterMessageId() {
            String externalId = m_manager.getServices().getMessageService().getLastExternalId(m_channel.getChannelId(), Direction.INCOMING);
            if (externalId != null) {
                try {
                    return Long.parseLong(externalId);
                }
                catch (NumberFormatException ex) {}
            }
            return null;
        }
    }

    /**
     * Gets the last follower id for this stream. Returned value may be null (new channel) or zero (no followers)
     * @return the Twitter user id
     */
    protected Long getLastFollowerId() {
        if (m_channel.getChannelBod() != null) {
            try {
                return Long.parseLong(m_channel.getChannelBod());
            }
            catch (NumberFormatException ex) {}
        }
        return null;
    }

    /**
     * Updates the last follower id for this stream
     * @param userId the user id from Twitter
     */
    protected void setLastFollowerId(long userId) {
        m_manager.getServices().getChannelService().updateChannelBod(m_channel.getChannelId(), String.valueOf(userId));
    }

    public ChannelContext getChannel() {
        return m_channel;
    }

    public StatsReporter.StatsTracker getStreamingStatistics() {
        return m_streamingClient.getStatsTracker();
    }

    public long getHandleId() {
        return m_handleId;
    }

    public boolean isBackfillComplete() {
        return m_backfillComplete;
    }

    public boolean isAutoFollow() {
        return m_autoFollow;
    }
}