# SMTP_Server

Email-Server using Java NIO, that implements a reduced version of SMTP protocol. The server supports commands HELO, MAIL FROM, RCPT TO, DATA, HELP and QUIT. 

Received e-mails are stored in a file named under convention <sender>_<message_id> under the directory <receiver>, where <sender> and <receiver> correspond to the e-mail address of appropriate sender and recipient. <message_id> is an integer value between 0 and 9999 randomly created by programm. 
