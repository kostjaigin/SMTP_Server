import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Combination of state and write control of client.
 */
class ClientControlState {
    // Write buffer will be pushed out as soon as the client becomes writable
    final ByteBuffer writeBuffer = ByteBuffer.allocate(1024);

    // Contains input sent by the client, which has not been parsed yet
    final StringBuilder clientInput = new StringBuilder();

    // Default charset defined by SMTP protocol
    private static final Charset messageCharset = StandardCharsets.US_ASCII;

    // Marks the client to close the connection, e.g. on QUIT
    private boolean markClosed = false;

    // Current client state
    enum State {
        COMMAND,    // Client sends commands
        DATA        // Client sends message data until <CR><LF>.<CR><LF>
    }
    State state = State.COMMAND;
    boolean saidHelo = false;

    // Mail meta data
    String from = "";
    final List<String> to = new ArrayList<>();

    ClientControlState() {
        // Signalise ready on create to client
        replyReady();
    }

    /**
     * Generic reply from server to queue up on buffer.
     *
     * @param code status code of reply.
     * @param message detailed description.
     */
    private void reply(Integer code, String message) {
        String reply = String.format("%d %s\r\n", code, message);
        writeBuffer.put(messageCharset.encode(reply));
    }

    /**
     * Generic reply from server to queue up on buffer indicating multi line message.
     *
     * @param code status code of reply.
     * @param message detailed description.
     */
    private void replyMultiline(Integer code, String message) {
        String reply = String.format("%d-%s\r\n", code, message);
        writeBuffer.put(messageCharset.encode(reply));
    }

    /**
     * Sends a ready status.
     */
    private void replyReady() {
        reply(220, "localhost VS SMTP ready, please have mercy");
    }

    /**
     * Sends a OK status.
     */
    void replyOK() {
        reply(250, "OK");
    }

    /**
     * Signals that client can send data.
     */
    void replyReadyForData() {
        reply(354, "End data with <CR><LF>.<CR><LF>");
    }

    /**
     * Sends confirmation to close the connection.
     */
    void replyBye() {
        reply(221, "Bye");
    }

    /**
     * Sends help containing supported commands.
     */
    void replyHelp() {
        replyMultiline(214, "This 'server' supports the following commands:");
        reply(214, "HELO MAIL RCPT DATA HELP QUIT");
    }

    /**
     * Sends a failure status due to an unrecognized or unsupported command.
     */
    void replyUnknownCommand() {
        reply(500, "Syntax error, command unrecognized");
    }

    /**
     * Send a failure status due to missing or wrong parameters.
     * E.g. > HELO
     */
    void replyInvalidParameters() {
        reply(501, "Syntax error in parameters or arguments");
    }

    /**
     * Send a failure status due to bad sequence of commands.
     * E.g. not providing MAIL FROM / RCPT TO before DATA
     */
    void replyBadSequence() {
        reply(503, "Bad sequence of commands");
    }

    /**
     * Send a failure status due to failed transaction.
     */
    void replyTransactionFailed() {
        reply(554, "Transaction failed");
    }

    /**
     * Mark the client as closed.
     */
    void close() {
        markClosed = true;
    }

    /**
     * Check is client has been marked closed.
     *
     * @return close status.
     */
    boolean isClosed() {
        return markClosed;
    }
}
