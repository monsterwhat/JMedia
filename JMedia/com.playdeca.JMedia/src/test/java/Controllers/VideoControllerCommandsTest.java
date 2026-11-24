package Controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import API.WS.VideoSocket;
import Models.Video.ProfileSessionState;
import Models.Video.Video;
import Services.ProfileSessionStateService;
import Services.VideoService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * The playing browser is the source of truth: the server relays its reports and
 * reaches it only through explicit commands (seek, set-playing). No server clock
 * advances the position.
 */
class VideoControllerCommandsTest {

    record Sent(String type, String payload, Long profileId) {}

    static class FakeSocket extends VideoSocket {
        final List<Sent> commands = new ArrayList<>();
        final List<ProfileSessionState> states = new ArrayList<>();

        @Override
        public void broadcastCommand(String commandType, JsonNode commandPayload, Long profileId) {
            commands.add(new Sent(commandType, commandPayload.toString(), profileId));
        }

        @Override
        public void broadcastAll(ProfileSessionState stateToBroadcast) {
            states.add(stateToBroadcast);
        }
    }

    static class FakeStates extends ProfileSessionStateService {
        final ProfileSessionState st = new ProfileSessionState();

        @Override
        public ProfileSessionState findByProfileId(Long profileId) {
            return st;
        }

        @Override
        public ProfileSessionState save(ProfileSessionState state) {
            return state;
        }
    }

    static class FakeVideos extends VideoService {
        @Override
        public Video find(Long id) {
            return null;
        }
    }

    private final FakeSocket socket = new FakeSocket();
    private final FakeStates states = new FakeStates();
    private final VideoController controller = new VideoController();

    VideoControllerCommandsTest() {
        controller.ws = socket;
        controller.profileSessionStateService = states;
        controller.videoService = new FakeVideos();
        controller.videoQueueController = new VideoQueueController();
        states.st.profileId = 7L;
        states.st.currentVideoId = 100L;
    }

    @Test
    void remoteSeekIsSentToThePlayerAsACommand() {
        controller.setSeconds(1234.5, 7L);
        assertEquals(1, socket.commands.size());
        assertEquals("seek", socket.commands.get(0).type());
        assertEquals("{\"value\":1234.5}", socket.commands.get(0).payload());
        assertEquals(7L, socket.commands.get(0).profileId());
    }

    @Test
    void remoteToggleSendsAnAbsolutePlayingCommand() {
        states.st.playing = true;
        controller.togglePlay(7L);
        assertFalse(states.st.playing);
        assertEquals("set-playing", socket.commands.get(0).type());
        assertEquals("{\"playing\":false}", socket.commands.get(0).payload());
        controller.togglePlay(7L);
        assertEquals("{\"playing\":true}", socket.commands.get(1).payload());
    }

    @Test
    void previousRestartSeeksThePlayerToZero() {
        states.st.currentTime = 600;
        controller.previous(7L);
        assertEquals(0, states.st.currentTime, 0.001);
        assertEquals("seek", socket.commands.get(0).type());
        assertEquals("{\"value\":0.0}", socket.commands.get(0).payload());
    }

    @Test
    void playerReportsAreRelayedButNeverCommanded() {
        controller.reportClientState(7L, 100L, true, 42.0);
        assertEquals(42.0, states.st.currentTime, 0.001);
        assertTrue(states.st.playing);
        assertEquals(1, socket.states.size(), "relayed to the profile's other devices");
        assertTrue(socket.commands.isEmpty(), "the reporting player is never commanded");
    }

    @Test
    void reportsFromAnotherVideoAreDropped() {
        controller.reportClientState(7L, 999L, true, 42.0);
        assertTrue(socket.states.isEmpty());
        assertEquals(0, states.st.currentTime, 0.001);
    }
}
