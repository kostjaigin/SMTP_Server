# SMTP_Server

Email-Server using Java NIO, that implements a reduced version of SMTP protocol. The server supports commands HELO, MAIL FROM, RCPT TO, DATA, HELP and QUIT. 

Received e-mails are stored in a file named under convention <sender>_<message_id> under the directory <receiver>, where <sender> and <receiver> correspond to the e-mail address of appropriate sender and recipient. <message_id> is an integer value between 0 and 9999 randomly created by programm. 
  
**Commands**

*HELO*
It’s the first SMTP command: is starts the conversation identifying the sender server and is generally followed by its domain name.

*MAIL FROM*
With this SMTP command the operations begin: the sender states the source email address in the “From” field and actually starts the email transfer.

*RCPT TO*
It identifies the recipient of the email; if there are more than one, the command is simply repeated address by address.

*DATA*
With the DATA command the email content begins to be transferred; it’s generally followed by a 354 reply code given by the server, giving the permission to start the actual transmission.

*HELP*
It’s a client’s request for some information that can be useful for the a successful transfer of the email.

*QUIT*
It terminates the SMTP conversation.
