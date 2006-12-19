package syndie.db;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import syndie.Constants;
import syndie.data.SyndieURI;

/**
 * contains a description of data that can be pulled from an archive, as well as
 * what type of data the archive would be interested in receiving.  serialized as
 * serialize(About)+numChannels+serialize(Channel[])+numMessages+serialize(Message[])
 */
public class SharedArchive {
    private Channel _channels[];
    private Message _messages[];
    private About _about;
    private long _loadedOn = System.currentTimeMillis();

    public static final int DEFAULT_MAX_SIZE_KB = 512;
    
    void setChannels(List channels) { _channels = (Channel[])channels.toArray(new Channel[0]); }
    void setMessages(List messages) { _messages = (Message[])messages.toArray(new Message[0]); }
    void setAbout(About about) { _about = about; }
    
    /** was the archive index loaded long enough ago that the remote archive should have rebuilt their index by now? */
    public boolean getRefreshable() {
        return _loadedOn + _about.getPublishRebuildFrequencyHours()*60*60*1000L < System.currentTimeMillis();
    }
    long getLoadDate() { return _loadedOn; }
    public int countTotalChannels() { return _channels.length; }
    public int countTotalMessages() { return _messages.length; }
    public int countNewChannels() {
        int rv = 0;
        for (int i = 0; i < _channels.length; i++)
            if (_channels[i].isNew())
                rv++;
        return rv;
    }
    public int countNewMessages() {
        int rv = 0;
        for (int i = 0; i < _messages.length; i++)
            if (_messages[i].isNew())
                rv++;
        return rv;
    }
    
    /** very inefficient, O(N) search */
    Channel getChannel(Hash chan) {
        for (int i = 0; i < _channels.length; i++) {
            if (DataHelper.eq(_channels[i].getScope(), chan.getData()))
                return _channels[i];
        }
        return null;
    }
    /** very inefficient, O(N) search */
    boolean isKnown(Hash chan, long messageId) {
        for (int i = 0; i < _messages.length; i++) {
            if (messageId == _messages[i].getMessageId()) {
                Channel scope = _channels[_messages[i].getScopeIndex()];
                if (DataHelper.eq(scope.getScope(), chan.getData()))
                    return true;
            }
        }
        return false;
    }
    
    /** is the channel info PBE encrypted? */
    private static final int FLAG_CHANNEL_PBE = 1 << 7;
    /** is the channel info publicly readable (publishes its BodyKey)? */
    private static final int FLAG_CHANNEL_PUBLIC = 1 << 6;
    /** has the channel info been updated lately? */
    private static final int FLAG_CHANNEL_ISNEW = 1 << 5;
    /** does the archive want to receive updates to the channel metadata? */
    private static final int FLAG_CHANNEL_WANTNEWMETA = 1 << 4;
    /** does the archive want to receive new messages in the channel? */
    private static final int FLAG_CHANNEL_WANTNEWMSG = 1 << 3;

    public static class Channel {
        /** 32 bytes */
        public byte[] _scope;
        /** 8 bytes */
        public long _version;
        /** 1 byte */
        public int _flags;
        
        public Channel() {
            _scope = null;
            _version = -1;
            _flags = 0;
        }
        
        public boolean isPBE() { return (_flags & FLAG_CHANNEL_PBE) != 0; }
        public boolean isPublic() { return (_flags & FLAG_CHANNEL_PUBLIC) != 0; }
        public boolean isNew() { return (_flags & FLAG_CHANNEL_ISNEW) != 0; }
        public boolean wantNewMeta() { return (_flags & FLAG_CHANNEL_WANTNEWMETA) != 0; }
        public boolean wantNewMsg() { return (_flags & FLAG_CHANNEL_WANTNEWMSG) != 0; }
        public byte[] getScope() { return _scope; }
        public long getVersion() { return _version; }
        
        public void setScope(Hash scope) { _scope = scope.getData(); }
        public void setVersion(long version) { _version = version; }
        public void setIsNew(boolean isNew) { _flags = (_flags & ~FLAG_CHANNEL_ISNEW) | (isNew ? FLAG_CHANNEL_ISNEW : 0); }
        public void setIsPBE(boolean isPBE) { _flags = (_flags & ~FLAG_CHANNEL_PBE) | (isPBE ? FLAG_CHANNEL_PBE : 0); }
        public void setIsPublic(boolean isPublic) { _flags = (_flags & ~FLAG_CHANNEL_PUBLIC) | (isPublic ? FLAG_CHANNEL_PUBLIC: 0); }
        public void setWantNewMeta(boolean want) { _flags = (_flags & ~FLAG_CHANNEL_WANTNEWMETA) | (want ? FLAG_CHANNEL_WANTNEWMETA : 0); }
        public void setWantNewMsgs(boolean want) { _flags = (_flags & ~FLAG_CHANNEL_WANTNEWMSG) | (want ? FLAG_CHANNEL_WANTNEWMSG : 0); }
        
        public void write(OutputStream out) throws IOException {
            out.write(_scope);
            try {
                DataHelper.writeLong(out, 8, _version);
                DataHelper.writeLong(out, 1, _flags);
            } catch (DataFormatException dfe) {
                throw new IOException("values out of range: " + dfe.getMessage());
            }
        }
        public void read(InputStream in) throws IOException {
            byte scope[] = new byte[Hash.HASH_LENGTH];
            int read = DataHelper.read(in, scope);
            if (read != scope.length)
                throw new IOException("Not enough data for the scope");
            try {
                long version = DataHelper.readLong(in, 8);
                int flags = (int)DataHelper.readLong(in, 1);
                _scope = scope;
                _version = version;
                _flags = flags;
            } catch (DataFormatException dfe) {
                throw new IOException("values out of range: " + dfe.getMessage());
            }
        }

        public String toString() {
            StringBuffer buf = new StringBuffer();
            buf.append(Base64.encode(_scope) + " / " + _version + " flags: ");
            if (isPBE()) buf.append("PBE ");
            if (isPublic()) buf.append("Public ");
            if (isNew()) buf.append("New ");
            if (wantNewMeta()) buf.append("WantNewMeta ");
            if (wantNewMsg()) buf.append("WantNewMsg ");
            buf.append("\n");
            return buf.toString();
        }

    }
    
    private static final int FLAG_MESSAGE_PBE = 1 << 7;
    private static final int FLAG_MESSAGE_PRIVATE = 1 << 6;
    private static final int FLAG_MESSAGE_PUBLIC = 1 << 5;
    private static final int FLAG_MESSAGE_ISNEW = 1 << 4;
    /** 0x00 means <= 4KB, 0x01 means <= 8KB, 0x02 means <= 16KB, etc, up through 0x0F, which means <= 128MB */
    private static final int FLAG_MESSAGE_SIZE_MASK = (1 << 4)-1;
    
    public static class Message {
        /** unique message id within the scope.  8 bytes */
        long _messageId;
        /** index into _channels for the scope the message was authorized in.  4 bytes */
        int _scopeChannel;
        /** index into _channels for the scope the message was targetting, or -1 if the same.  4 bytes */
        int _targetChannel;
        /** 1 byte */
        int _flags;
        
        public Message() {
            _messageId = -1;
            _scopeChannel = -1;
            _targetChannel = -1;
            _flags = 0;
        }
        
        public boolean isPBE() { return (_flags & FLAG_MESSAGE_PBE) != 0; }
        public boolean isPrivate() { return (_flags & FLAG_MESSAGE_PRIVATE) != 0; }
        public boolean isPublic() { return (_flags & FLAG_MESSAGE_PUBLIC) != 0; }
        public boolean isNew() { return (_flags & FLAG_MESSAGE_ISNEW) != 0; }
        public long getMaxSizeKB() { return 1 << (2+(_flags & FLAG_MESSAGE_SIZE_MASK)); }
        public int getScopeIndex() { return _scopeChannel; }
        public int getTargetIndex() { return _targetChannel; }
        public long getMessageId() { return _messageId; }
        public void setSize(long bytes) {
            long kb = (bytes + 1023)/1024;
            kb >>= 2;
            int count = 0;
            while (kb > 0) {
                count++;
                kb >>= 1;
            }
            _flags = (_flags & ~FLAG_MESSAGE_SIZE_MASK) | count;
        }
        public void setMessageId(long id) { _messageId = id; }
        public void setScope(int channelIndex) { _scopeChannel = channelIndex; }
        public void setTarget(int channelIndex) { _targetChannel = channelIndex; }
        public void setIsNew(boolean isNew) { _flags = (_flags & ~FLAG_MESSAGE_ISNEW) | (isNew ? FLAG_MESSAGE_ISNEW : 0); }
        public void setIsPBE(boolean isPBE) { _flags = (_flags & ~FLAG_MESSAGE_PBE) | (isPBE ? FLAG_MESSAGE_PBE : 0); }
        public void setIsPrivate(boolean isPrivate) { _flags = (_flags & ~FLAG_MESSAGE_PRIVATE) | (isPrivate ? FLAG_MESSAGE_PRIVATE : 0); }
        public void setIsPublic(boolean isPublic) { _flags = (_flags & ~FLAG_MESSAGE_PUBLIC) | (isPublic ? FLAG_MESSAGE_PUBLIC : 0); }
        
        public void write(OutputStream out) throws IOException {
            try {
                DataHelper.writeLong(out, 8, _messageId);
                DataHelper.writeLong(out, 4, _scopeChannel);
                DataHelper.writeLong(out, 4, _targetChannel);
                DataHelper.writeLong(out, 1, _flags);
            } catch (DataFormatException dfe) {
                throw new IOException("values out of range: " + dfe.getMessage());
            }
        }
        public void read(InputStream in) throws IOException {
            try {
                long messageId = DataHelper.readLong(in, 8);
                long scope = DataHelper.readLong(in, 4);
                long target = DataHelper.readLong(in, 4);
                long flags = DataHelper.readLong(in, 1);
                _messageId = messageId;
                _scopeChannel = (int)scope;
                _targetChannel = (int)target;
                _flags = (int)flags;
            } catch (DataFormatException dfe) {
                throw new IOException("values out of range: " + dfe.getMessage());
            }
        }
        
        public String toString() {
            StringBuffer buf = new StringBuffer();
            buf.append("* msg: " + _messageId + " scope: " + _scopeChannel + " target: " + _targetChannel + " flags: ");
            if (isPBE()) buf.append("PBE ");
            if (isPrivate()) buf.append("Private ");
            if (isPublic()) buf.append("Public ");
            if (isNew()) buf.append("New ");
            buf.append("size: ").append(getMaxSizeKB());
            buf.append("\n");
            return buf.toString();
        }
    }
    
    private static final int FLAG_ABOUT_WANT_RECENT_ONLY = 1 << 15;
    private static final int FLAG_ABOUT_WANT_KNOWN_ONLY = 1 << 14;
    private static final int FLAG_ABOUT_WANT_PBE = 1 << 13;
    private static final int FLAG_ABOUT_WANT_PRIVATE = 1 << 12;
    private static final int FLAG_ABOUT_POST_REQUIRES_PASSPHRASE = 1 << 11;
    private static final int FLAG_ABOUT_HASHCASH_MINSIZE_MASK = (((1 << 11)-1) & ~((1 << 6)-1));
    private static final int FLAG_ABOUT_HASHCASH_MINSIZE_RSHIFT = 6;
    private static final int FLAG_ABOUT_REPUBLISH_FREQUENCY_MASK = (((1 << 6)-1) & ~((1 << 4)-1));
    private static final int FLAG_ABOUT_REPUBLISH_FREQUENCY_RSHIFT = 4;
    private static final int FLAG_ABOUT_MAXSIZE_MASK = (1 << 4)-1;
    
    static final int ABOUT_NO_ADMIN_CHANNEL = (1 << 31)-1;
    
    public static class About {
        /** 2 bytes */
        int _flags;
        /** index into _channels for the scope that the administrtator of the archive uses, or (2^32-1) for none.  4 bytes */
        int _adminChannel;
        /** 1 + sizeof(archiveURIs) bytes */
        SyndieURI _archiveURIs[];
        
        public About() {
            _flags = 0;
            _adminChannel = ABOUT_NO_ADMIN_CHANNEL;
            _archiveURIs = null;
        }
        
        public boolean wantRecentOnly() { return (_flags & FLAG_ABOUT_WANT_RECENT_ONLY) != 0; }
        public boolean wantKnownChannelsOnly() { return (_flags & FLAG_ABOUT_WANT_KNOWN_ONLY) != 0; }
        public boolean wantPBE() { return (_flags & FLAG_ABOUT_WANT_PBE) != 0; }
        public boolean wantPrivate() { return (_flags & FLAG_ABOUT_WANT_PRIVATE) != 0; }
        public boolean postRequiresPassphrase() { return (_flags & FLAG_ABOUT_POST_REQUIRES_PASSPHRASE) != 0; }
        /** posting messages at or above this size (in kilobytes) requires a hashcash certificate for the message */
        public int minMessageSizeKBRequiringHashcash() { 
            int sizeShift = (_flags & FLAG_ABOUT_HASHCASH_MINSIZE_MASK) >> FLAG_ABOUT_HASHCASH_MINSIZE_RSHIFT;
            sizeShift += 5;
            return 1 << sizeShift; // values range from 32KB to 1MB
        }
        /** never send the archive more than this size (in kilobytes) */
        public int maxMessageSize() { 
            int sizeShift = _flags & FLAG_ABOUT_MAXSIZE_MASK;
            return 1 << sizeShift; // values range from 1KB to 32MB
        }
        public void setMaxMessageSize(int kb) {
            int count = 0;
            while (kb > 1) {
                kb >>= 1;
                count++;
            }
            _flags = (_flags & ~FLAG_ABOUT_MAXSIZE_MASK) | count;
        }
        /** how often this shared archive index is rebuilt (so no point in fetching or pushing more often than this) */
        public int getPublishRebuildFrequencyHours() { 
            int period = (_flags & FLAG_ABOUT_REPUBLISH_FREQUENCY_MASK) >> FLAG_ABOUT_REPUBLISH_FREQUENCY_RSHIFT;
            switch (period) {
                case 0: return 1;
                case 1: return 6;
                case 2: return 12;
                case 3: 
                default:
                    return 24;
            }
        }
        public SyndieURI[] getAlternateArchives() { return _archiveURIs; }
        
        public void setAdminChannel(int channelIndex) { _adminChannel = channelIndex; }
        public void setPublishRebuildFrequencyHours(int numHours) {
            int flagVal = 0;
            if (numHours <= 1) flagVal = 0;
            else if (numHours <= 6) flagVal = 1;
            else if (numHours <= 12) flagVal = 2;
            else flagVal = 3;
            _flags = (_flags & ~FLAG_ABOUT_REPUBLISH_FREQUENCY_MASK) | (flagVal << FLAG_ABOUT_REPUBLISH_FREQUENCY_RSHIFT);
        }
        public void setAlternativeArchives(SyndieURI archives[]) { _archiveURIs = archives; }
        public void setMinMessageSizeKBRequiringHashcash(int kb) {
            int remaining = kb >> 5;
            int shifts = 0;
            while (remaining > 1) {
                shifts++;
                remaining >>= 1;
            }
            _flags = (_flags & ~FLAG_ABOUT_HASHCASH_MINSIZE_MASK) | (shifts << FLAG_ABOUT_HASHCASH_MINSIZE_RSHIFT);
        }
        public void setPostingRequiresPassphrase(boolean requires) {
            _flags = (_flags & ~FLAG_ABOUT_POST_REQUIRES_PASSPHRASE) | (requires ? FLAG_ABOUT_POST_REQUIRES_PASSPHRASE : 0);
        }
        public void setWantPrivate(boolean wantPrivate) {
            _flags = (_flags & ~FLAG_ABOUT_WANT_PRIVATE) | (wantPrivate ? FLAG_ABOUT_WANT_PRIVATE : 0);
        }
        public void setWantPBE(boolean wantPBE) {
            _flags = (_flags & ~FLAG_ABOUT_WANT_PBE) | (wantPBE ? FLAG_ABOUT_WANT_PBE : 0);
        }
        public void setWantKnownChannelsOnly(boolean wantKnownChannelsOnly) {
            _flags = (_flags & ~FLAG_ABOUT_WANT_KNOWN_ONLY) | (wantKnownChannelsOnly ? FLAG_ABOUT_WANT_KNOWN_ONLY : 0);
        }
        public void setWantRecentOnly(boolean wantRecentOnly) {
            _flags = (_flags & ~FLAG_ABOUT_WANT_RECENT_ONLY) | (wantRecentOnly ? FLAG_ABOUT_WANT_RECENT_ONLY : 0);
        }

        public void write(OutputStream out) throws IOException {
            try {
                DataHelper.writeLong(out, 2, _flags);
                DataHelper.writeLong(out, 4, _adminChannel);
                DataHelper.writeLong(out, 1, (_archiveURIs != null ? _archiveURIs.length : 0));
                if (_archiveURIs != null) {
                    for (int i = 0; i < _archiveURIs.length; i++) {
                        byte data[] = DataHelper.getUTF8(_archiveURIs[i].toString());
                        DataHelper.writeLong(out, 2, data.length);
                        out.write(data);
                    }
                }
            } catch (DataFormatException dfe) {
                throw new IOException("values out of range: " + dfe.getMessage());
            }
        }
        public void read(InputStream in) throws IOException {
            try {
                int flags = (int)DataHelper.readLong(in, 2);
                long chan = DataHelper.readLong(in, 4);
                int numArchives = (int)DataHelper.readLong(in, 1);
                SyndieURI uris[] = new SyndieURI[numArchives];
                for (int i = 0; i < numArchives; i++) {
                    long len = DataHelper.readLong(in, 2);
                    byte data[] = new byte[(int)len];
                    int read = DataHelper.read(in, data);
                    if (read != len)
                        throw new IOException("Not enough data for archive");
                    String uriStr = DataHelper.getUTF8(data);
                    try {
                        SyndieURI uri = new SyndieURI(uriStr);
                        uris[i] = uri;
                    } catch (URISyntaxException use) {
                        throw new IOException("Invalid archive URI: " + uriStr);
                    }
                }
                _flags = flags;
                _adminChannel = (int)chan;
                _archiveURIs = uris;
            } catch (DataFormatException dfe) {
                throw new IOException("values out of range: " + dfe.getMessage());
            }
        }
        
        public String toString() {
            StringBuffer buf = new StringBuffer();
            buf.append("* Archive about:\n");
            buf.append("= flags: ");
            if (wantKnownChannelsOnly()) buf.append("KnownOnly ");
            if (wantRecentOnly()) buf.append("RecentOnly ");
            if (!wantPBE()) buf.append("NoPBE ");
            if (!wantPrivate()) buf.append("NoPrivate ");
            buf.append("\n");
            buf.append("= MaxSize wanted: ").append(maxMessageSize()).append("KB\n");
            buf.append("= MinSize for hashcash: ").append(minMessageSizeKBRequiringHashcash()).append("KB\n");
            buf.append("= Rebuild frequency: ").append(getPublishRebuildFrequencyHours()).append(" hours\n");
            buf.append("= Passphrase required to post? ").append(postRequiresPassphrase()).append("\n");
            buf.append("= Archive admin channel: " + (_adminChannel == ABOUT_NO_ADMIN_CHANNEL ? "none" : ""+_adminChannel) + "\n");
            buf.append("= URIs: \n");
            if ( (_archiveURIs != null) && (_archiveURIs.length > 0) ) {
                for (int i = 0; i < _archiveURIs.length; i++)
                    buf.append("\t").append(_archiveURIs.toString()).append("\n");
            } else {
                buf.append("\tnone\n");
            }
            return buf.toString();
        }
    }

    /** serialize(About)+numChannels+serialize(Channel[])+numMessages+serialize(Message[]) */
    public void write(OutputStream out) throws IOException {
        try {
            _about.write(out);
            DataHelper.writeLong(out, 4, (_channels != null ? _channels.length : 0));
            if (_channels != null)
                for (int i = 0; i < _channels.length; i++)
                    _channels[i].write(out);
            DataHelper.writeLong(out, 4, (_messages != null ? _messages.length : 0));
            if (_messages != null)
                for (int i = 0; i < _messages.length; i++)
                    _messages[i].write(out);
        } catch (DataFormatException dfe) {
            throw new IOException("values out of range: " + dfe.getMessage());
        }
    }
    
    public void read(InputStream in) throws IOException {
        try {
            About about = new About();
            about.read(in);
            long numChannels = DataHelper.readLong(in, 4);
            Channel chans[] = new Channel[(int)numChannels];
            for (int i = 0; i < chans.length; i++) {
                chans[i] = new Channel();
                chans[i].read(in);
            }
            long numMessages = DataHelper.readLong(in, 4);
            Message msgs[] = new Message[(int)numMessages];
            for (int i = 0; i < msgs.length; i++) {
                msgs[i] = new Message();
                msgs[i].read(in);
            }

            _about = about;
            _channels = chans;
            _messages = msgs;
        } catch (DataFormatException dfe) {
            throw new IOException("Corrupt archive: " + dfe.getMessage());
        }
    }
    
    public String toString() {
        StringBuffer buf = new StringBuffer();
        buf.append(_about.toString());
        buf.append("channels: ").append(_channels.length).append("\n");
        for (int i = 0; i < _channels.length; i++)
            buf.append(i).append(": ").append(_channels[i].toString());
        buf.append("messages: ").append(_messages.length).append("\n");
        for (int i = 0; i < _messages.length; i++)
            buf.append(_messages[i].toString());
        return buf.toString();
    }
    
    public static class PullStrategy {
        public PullStrategy() {
            maxKBPerMessage = DEFAULT_MAX_SIZE_KB;
        }
        /**
         * if a message exceeds this size, do not pull it
         */
        public int maxKBPerMessage;
        /**
         * how much data we will pull across all of the messages (ignoring metadata size
         * and transmission overhead)
         */
        public int maxKBTotal;
        /** 
         * if true, only pull down messages and/or metadata for channels we already
         * know locally
         */
        public boolean knownChannelsOnly;
        /**
         * if true, pull down messages encrypted to forum owner(s) (even if that
         * isn't us)
         */
        public boolean includePrivateMessages;
        /**
         * if true, pull down messages that are encrypted with a passphrase 
         */
        public boolean includePBEMessages;
        /**
         * should we only fetch messages the archive advertizes as 'recent', even if they
         * have messages we would otherwise want but are flagged as 'old'?
         */
        public boolean includeRecentMessagesOnly;
        /**
         * if true, we want to use trivial single-database private information retrieval:
         * get everything the archive advertizes as "new", and their dependencies, even if
         * we already have some of them.  This way the archive can't tell what we have or
         * what we want (and hence will have a much harder time profiling the user).  If
         * this flag is set, all of the other flags in the strategy are ignored.
         */
        public boolean includeDupForPIR;
        
        public String toString() {
            StringBuffer buf = new StringBuffer();
            if (includeDupForPIR) {
                buf.append("PIR ");
            } else {
                if (includeRecentMessagesOnly) buf.append("RecentMessagesOnly ");
                if (includePBEMessages)
                    buf.append("IncludePBE ");
                else
                    buf.append("DontIncludePBE ");
                if (includePrivateMessages)
                    buf.append("IncludePrivate ");
                else
                    buf.append("DontIncludePrivate ");
                if (knownChannelsOnly)
                    buf.append("KnownChannelsOnly ");
                else
                    buf.append("AllChannels ");
            }
            return buf.toString();
        }
    }

    /** 
     * pick out what elements of the shared archive we want, according to the given
     * strategy.  the elements are ordered so as to include dependencies first, though
     * for parallel execution, all metadata URIs should be run prior to the messages.
     *
     */
    public List selectURIsToPull(DBClient client, UI ui, PullStrategy strategy) {
        int totalAllocatedKB = 0;
        List uris = new ArrayList();
        for (int i = 0; i < _channels.length; i++) {
            Hash scope = new Hash(_channels[i].getScope());
            if (_channels[i].isNew() && strategy.includeDupForPIR) {
                uris.add(SyndieURI.createScope(scope));
            } else {
                long knownVersion = client.getChannelVersion(scope);
                if (strategy.knownChannelsOnly && (knownVersion < 0))
                    continue;
                if (_channels[i].getVersion() > knownVersion) {
                    uris.add(SyndieURI.createScope(scope));
                } else {
                    // already known.  no need
                }
            }
        }
        for (int i = 0; i < _messages.length; i++) {
            Hash scope = new Hash(_channels[_messages[i].getScopeIndex()].getScope());
            Hash target = new Hash(_channels[_messages[i].getTargetIndex()].getScope());
            if (_messages[i].isNew() && strategy.includeDupForPIR) {
                SyndieURI scopeURI = SyndieURI.createScope(scope);
                SyndieURI targetURI = SyndieURI.createScope(target);
                if (!uris.contains(scopeURI))
                    uris.add(scopeURI);
                if (!uris.contains(targetURI))
                    uris.add(targetURI);
                uris.add(SyndieURI.createMessage(scope, _messages[i].getMessageId()));
                totalAllocatedKB += _messages[i].getMaxSizeKB();
            } else {
                if ( (strategy.maxKBPerMessage > 0) && (_messages[i].getMaxSizeKB() > strategy.maxKBPerMessage) )
                    continue;
                if (_messages[i].isPBE() && !strategy.includePBEMessages)
                    continue;
                if (_messages[i].isPrivate() && !strategy.includePrivateMessages)
                    continue;
                if (_messages[i].isNew() && !strategy.includeRecentMessagesOnly)
                    continue;
                long targetChanId = client.getChannelId(target);
                long scopeChanId = client.getChannelId(scope);
                if ( ( (scopeChanId < 0) || (targetChanId < 0) ) && (strategy.knownChannelsOnly) )
                    continue;
                
                if ( (strategy.maxKBTotal > 0) && (totalAllocatedKB + _messages[i].getMaxSizeKB() > strategy.maxKBTotal) )
                    continue;
                
                SyndieURI scopeURI = SyndieURI.createScope(scope);
                SyndieURI targetURI = SyndieURI.createScope(target);
                if (!uris.contains(scopeURI))
                    uris.add(scopeURI);
                if (!uris.contains(targetURI))
                    uris.add(targetURI);
                totalAllocatedKB += _messages[i].getMaxSizeKB();
                uris.add(SyndieURI.createMessage(scope, _messages[i].getMessageId()));
            }
        }
        ui.debugMessage("Pull: strategy=" + strategy + " Total allocated KB: " + totalAllocatedKB + " URIs: " + uris);
        return uris;
    }
    
    public static class PushStrategy {
        public PushStrategy() {
            maxKBPerMessage = DEFAULT_MAX_SIZE_KB;
        }
        /**
         * if a message exceeds this size, do not push it
         */
        public int maxKBPerMessage;
        /**
         * how much data we will push across all of the messages (ignoring metadata size
         * and transmission overhead)
         */
        public int maxKBTotal;
        /**
         * if true, generate hashcash certs for locally created messages that
         * the remote archive wants certs for
         */
        public boolean sendHashcashForLocal;
        /**
         * if true, generate hashcash certs for all messages that
         * the remote archive wants certs for
         */
        public boolean sendHashcashForAll;
        /**
         * only send messages we generated locally in the last few days that
         * the archive doesnt have yet.  this has obvious anonymity attributes
         */
        public boolean sendLocalNewOnly;
        public String toString() {
            StringBuffer buf = new StringBuffer();
            if (sendHashcashForLocal) buf.append("HCForLocal ");
            if (sendHashcashForAll) buf.append("HCForAll ");
            if (sendLocalNewOnly)
                buf.append("LocalNewOnly ");
            else
                buf.append("AllDiff ");
            return buf.toString();
        }
    }
    
    public List selectURIsToPush(DBClient client, UI ui, PushStrategy strategy) {
        /** SyndieURI of a message to the SyndieURI of a scope it depends on */
        Map dependencies = new HashMap();
        List rv = new ArrayList();
        if (strategy.sendLocalNewOnly) // local new == messages in our ./outbound/*/ directories
            scheduleNew(client, ui, rv, dependencies, client.getOutboundDir(), strategy);
        else // otherwise, push new (etc) from our ./archive/*/ directories
            scheduleNew(client, ui, rv, dependencies, client.getArchiveDir(), strategy);
        resolveDependencies(client, rv, dependencies);
        
        ui.debugMessage("Push: strategy=" + strategy + " URIs: " + rv);
        return rv;
    }
    
    private void resolveDependencies(DBClient client, List rv, Map dependencies) {
        for (Iterator iter = dependencies.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry entry = (Map.Entry)iter.next();
            SyndieURI msgURI = (SyndieURI)entry.getKey();
            SyndieURI chanURI = (SyndieURI)entry.getValue();
            if (rv.contains(chanURI))
                continue; // ok, already scheduled
            
            if (getChannel(chanURI.getScope()) == null)
                continue; // ok, they already have it (or at least some version of it)
            
            if (_about.wantKnownChannelsOnly()) {
                // boo.  dependency failed because they are no fun.
                rv.remove(msgURI);
                continue;
            }
            
            File meta = new File(new File(client.getArchiveDir(), chanURI.getScope().toBase64()), "meta" + Constants.FILENAME_SUFFIX);
            if (meta.exists()) {
                rv.add(chanURI);
            } else {
                // dependency failed because we don't keep full archives
                rv.remove(msgURI);
                continue;
            }
        }
    }
    
    private void scheduleNew(DBClient client, UI ui, List rv, Map dependencies, File dir, PushStrategy strategy) {
        long totalKB = 0;
        File dirs[] = dir.listFiles(new FileFilter() {
            public boolean accept(File pathname) {
                return pathname.isDirectory() && !pathname.getName().startsWith(".");
            }
        });
        
        SharedArchiveBuilder.sortFiles(dirs);
        
        for (int i = 0; i < dirs.length; i++) {
            Hash scope = new Hash(Base64.decode(dirs[i].getName()));
            long version = client.getChannelVersion(scope);

            Channel remChan = getChannel(scope);
            if (_about.wantKnownChannelsOnly() && (remChan == null))
                continue;

            boolean sendMeta = false;
            if ( (remChan == null) || (remChan.getVersion() < version) )
                sendMeta = true;

            File metaFile = new File(dirs[i], "meta" + Constants.FILENAME_SUFFIX);
            if (sendMeta) {
                if (metaFile.exists())
                    rv.add(SyndieURI.createScope(scope));
                else // we need to send them meta, but don't have it.  boo, hiss
                    continue;
            }

            File files[] = dirs[i].listFiles(new FileFilter() {
                public boolean accept(File pathname) {
                    String name = pathname.getName();
                    return (name.endsWith(Constants.FILENAME_SUFFIX) && !name.startsWith("meta"));
                }
            });

            SharedArchiveBuilder.sortFiles(files);
            
            for (int j = 0; j < files.length; j++) {
                long messageId = SharedArchiveBuilder.getMessageId(files[j]);
                if (messageId < 0)
                    continue;
                boolean known = isKnown(scope, messageId);
                if (known)
                    continue;

                long lenKB = (files[j].length()+1023)/1024;
                if (lenKB > _about.maxMessageSize())
                    continue;
                if ( (strategy.maxKBPerMessage > 0) && (lenKB > strategy.maxKBPerMessage) )
                    continue;

                long msgId = client.getMessageId(scope, messageId);
                int privacy = client.getMessagePrivacy(msgId);
                if (!_about.wantPBE() && (privacy == DBClient.PRIVACY_PBE))
                    continue;
                if (!_about.wantPrivate() && (privacy == DBClient.PRIVACY_PRIVREPLY))
                    continue;

                if (_about.wantRecentOnly()) {
                    long when = client.getMessageImportDate(msgId);
                    if (when + SharedArchiveBuilder.PERIOD_NEW < System.currentTimeMillis())
                        continue;
                }

                if ( (strategy.maxKBTotal > 0) && (lenKB + totalKB > strategy.maxKBTotal))
                    continue;

                long authorId = client.getMessageAuthor(msgId);
                long targetId = client.getMessageTarget(msgId);
                long scopeId = client.getChannelId(scope);

                totalKB += lenKB;
                SyndieURI msgURI = SyndieURI.createMessage(scope, messageId);
                rv.add(msgURI);

                if ( (targetId >= 0) && (scopeId != targetId) ) {
                    Hash target = client.getChannelHash(targetId);
                    SyndieURI uri = SyndieURI.createScope(target);
                    dependencies.put(msgURI, uri);
                } else if ( (authorId >= 0) && (scopeId != authorId) ) {
                    Hash author = client.getChannelHash(authorId);
                    SyndieURI uri = SyndieURI.createScope(author);
                    dependencies.put(msgURI, uri);
                }
            }
        }
    }
}
