import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SMTPServer {
    // Default logger
    private static final Logger log = Logger.getLogger(SMTPServer.class.getName());

    // Byte buffer used for read and write
    private static final ByteBuffer buffer = ByteBuffer.allocate(1024);

    // Default charset defined by SMTP protocol
    private static final Charset messageCharset = StandardCharsets.US_ASCII;

    /**
     * Program entrypoint.
     *
     * @param args first argument is the binding TCP port.
     */
    public static void main(String[] args) {
        // First argument must be a TCP port
        if(args.length != 1) {
            printUsage();
            return;
        }

        // Convert port from args to integer
        int port = 0;
        try {
            port = Integer.parseInt(args[0]);
        } catch(NumberFormatException e) {
            log.severe("invalid port: " + args[0]);
            System.exit(1);
        }

        // Run SMTP server implementation
        try {
            run(port);
        } catch (IOException e) {
            log.severe("IO error: " + e.toString());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Prints simple usage message on wrong args.
     */
    private static void printUsage() {
        System.out.println("port is required as first argument.");
    }

    /**
     * Runs the SMTP server implementation.
     *
     * @param port binding TCP port.
     * @throws IOException occurs on several socket/channel actions.
     */
    private static void run(int port) throws IOException {
        // Initialize server
        Selector selector = Selector.open();
        ServerSocketChannel socketChannel = ServerSocketChannel.open();
        socketChannel.configureBlocking(false);
        socketChannel.socket().bind(new InetSocketAddress(port));

        // Subscribe to acceptable connections
        socketChannel.register(selector, SelectionKey.OP_ACCEPT);
        
        // Run the server non-stop
        for(;;) {
            // Wait for ready events (blocking)
            if(selector.select() == 0) {
                continue;
            }

            // Get the set of selected ready keys
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> it = selectedKeys.iterator();

            // Iterate over selected ready keys
            while(it.hasNext()) {
                SelectionKey key = it.next();

                // Remove handled key from ready set
                it.remove();

                // Client can be accepted
                if(key.isAcceptable()) {
                    ServerSocketChannel sock = (ServerSocketChannel) key.channel();

                    // Accept new client
                    SocketChannel client = sock.accept();
                    client.configureBlocking(false);

                    // Subscribe to client's read and write ready set events
                    SelectionKey clientKey = client.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);

                    // Create client state and attach to session
                    clientKey.attach(new ClientControlState());
                }

                // Client is ready to send
                if(key.isReadable()) {
                    SocketChannel channel = (SocketChannel) key.channel();

                    // Get client's state and control
                    ClientControlState client = (ClientControlState) key.attachment();

                    // Read message
                    buffer.clear();
                    if(channel.read(buffer) == -1) {
                        // Client has closed the connection
                        channel.close();
                        continue;
                    }
                    buffer.flip();

                    // Store input in client's state
                    client.clientInput.append(messageCharset.decode(buffer));

                    String message;
                    int delimiterIndex;
                    // Check if client is sending commands or data
                    switch(client.state) {
                        // TODO does client needs to wait for reply?
                        case COMMAND:
                            // Extract complete commands from input and dispatch to handlers
                            while((delimiterIndex = client.clientInput.indexOf("\r\n")) != -1
                                    && client.state == ClientControlState.State.COMMAND) {
                                // Changing state to DATA here would exit the command parsing
                                message = client.clientInput.substring(0, delimiterIndex);
                                client.clientInput.delete(0, delimiterIndex + 2); // ASK: Why + 2 here?

                                log.info("received command: " + message);
                                dispatchMessage(message, client);
                            }

                        case DATA:
                            // Extract data from input and dispatch to handlers
                            while((delimiterIndex = client.clientInput.indexOf("\r\n.\r\n")) != -1
                                    && client.state == ClientControlState.State.DATA) {
                                // Changing state to COMMAND here would exit the command parsing
                                message = client.clientInput.substring(0, delimiterIndex);
                                client.clientInput.delete(0, delimiterIndex + 5); // ASK: And WHY + 5 here?

                                log.info("received data: " + message);
                                dispatchData(message, client);
                            }
                            break;
                    }

                    // Close client's channel if client has been marked closed by any handler
                    if(client.isClosed()) {
                        channel.close();
                        continue;
                    }
                }

                // Client is ready to receive
                if(key.isWritable()) {
                    // Get client's state and control
                    ClientControlState client = (ClientControlState) key.attachment();

                    // Check if something needs to be written to client
                    if(client.writeBuffer.position() != 0) {
                        SocketChannel channel = (SocketChannel) key.channel();

                        // Write buffer to client
                        client.writeBuffer.flip();
                        try {
                            channel.write(client.writeBuffer);
                        } catch (IOException e) {
                            log.info("write failed: client has closed the connection");
                            // Close channel, connection has been terminated
                            channel.close();
                        }
                        client.writeBuffer.clear();
                    }
                }
            }
        }
    }

    /**
     * Dispatches messages to according handlers for commands.
     *
     * @param message received message from client.
     * @param client clients control and state.
     */
    private static void dispatchMessage(String message, ClientControlState client) {
        // Split whitespace separated command and remove \r\n
        String[] splitted = message.split(" ", 2);

        // Commands are case insensitive
        String command = splitted[0].toLowerCase();

        // Parameters are extracted if available. Requirement will be checked on lower command level.
        String parameters = splitted.length == 2 ? splitted[1] : "";

        // Dispatch commands
        switch(command) {
            case "helo":
                handleHelo(parameters, client);
                break;

            case "help":
                handleHelp(parameters, client);
                break;

            case "quit":
                handleQuit(parameters, client);
                break;

            case "data":
                handleData(parameters, client);
                break;

            case "mail":
                handleMail(parameters, client);
                break;

            case "rcpt":
                handleRcpt(parameters, client);
                break;

            default:
                client.replyUnknownCommand();
        }
    }

    /**
     * Final handler on mail dispatching data.
     *
     * @param message command parameters.
     * @param client clients control and state.
     */
    private static void dispatchData(String message, ClientControlState client) {
        // Generate a random ID for mail
        int id = new Random().nextInt(9999);

        // Send mail to all recipients
        for(String recipient : client.to) {
            // Create file path in format: <receiver>/<sender>_<message_id>
            Path path = Paths.get(String.format("%s%c%s_%d", recipient, File.separatorChar, client.from, id));

            // Create directory, file, open output stream and write message to file
            try {
                Files.createDirectories(path.getParent());
                Files.createFile(path);
                FileOutputStream file = new FileOutputStream(path.toFile());
                FileChannel channel = file.getChannel();
                channel.write(messageCharset.encode(message));
                channel.close();
            } catch (IOException e) {
                client.replyTransactionFailed();
                e.printStackTrace();
                return;
            }
        }

        // Cleanup clients state
        client.state = ClientControlState.State.COMMAND;

        client.replyOK();
    }

    /**
     * Handler for HELO command.
     *
     * @param message command parameters.
     * @param client clients control and state.
     */
    private static void handleHelo(String message, ClientControlState client) {
        if(message.isEmpty()) {
            client.replyInvalidParameters();
        } else {
            client.saidHelo = true;
            client.replyOK();
        }
    }

    /**
     * Handler for HELP command.
     *
     * @param message command parameters.
     * @param client clients control and state.
     */
    private static void handleHelp(String message, ClientControlState client) {
        if(message.isEmpty()) {
            client.replyHelp();
        } else {
            client.replyUnknownCommand();
        }
    }

    /**
     * Handler for QUIT command.
     *
     * @param message command parameters.
     * @param client clients control and state.
     */
    private static void handleQuit(String message, ClientControlState client) {
        if(message.isEmpty()) {
            client.replyBye();
            client.close();
        } else {
            client.replyUnknownCommand();
        }
    }

    /**
     * Handler for DATA command.
     *
     * @param message command parameters.
     * @param client clients control and state.
     */
    private static void handleData(String message, ClientControlState client) {
        // DATA has no parameters
        if(!message.isEmpty()) {
            client.replyUnknownCommand();
            return;
        }

        // Check for required mail fields to be set
        if(client.from.isEmpty() || client.to.isEmpty() || !client.saidHelo) {
            client.replyBadSequence();
            return;
        }

        client.replyReadyForData();
        client.state = ClientControlState.State.DATA;
    }

    /**
     * Handler for MAIL command.
     *
     * @param message command parameters.
     * @param client clients control and state.
     */
    private static void handleMail(String message, ClientControlState client) {
        if(message.isEmpty()) {
            client.replyInvalidParameters();
            return;
        }

        if(!client.saidHelo) {
            client.replyBadSequence();
            return;
        }

        // Extract from-address (validation omitted), ignore case on 'FROM'
        // disallowed empty from field on purpose, due to file storage and naming
        Matcher matcher = Pattern.compile("^(?i)from:<(.+)>$").matcher(message);

        if(matcher.matches()) {
            // Cleanup RCPT according to rfc5321 section 3.3, MAIL resets the state
            client.to.clear();

            client.from = matcher.group(1);
            client.replyOK();
        } else {
            client.replyInvalidParameters();
        }
    }

    /**
     * Handler for RCPT command.
     *
     * @param message command parameters.
     * @param client clients control and state.
     */
    private static void handleRcpt(String message, ClientControlState client) {
        if(message.isEmpty()) {
            client.replyInvalidParameters();
            return;
        }

        if(!client.saidHelo) {
            client.replyBadSequence();
            return;
        }

        // Extract to-address (validation omitted), ignore case on 'TO'
        Matcher matcher = Pattern.compile("^(?i)to:<(.+)>$").matcher(message);

        if(matcher.matches()) {
            client.to.add(matcher.group(1));
            client.replyOK();
        } else {
            client.replyInvalidParameters();
        }
    }
}