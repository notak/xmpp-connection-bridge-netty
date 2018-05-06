# Netty xmpp Connection Bridge

For people who are still using XMPP, the servers involved can be crusty old things, configured by people who are long-gone and left to rot in a darkened corner of the cloud. Although ejabberd and the other major vendors enabled the XMPP websocket interface in the software years ago, many servers haven't caught up. Obviously the "right" solution to this is to find the server and sort the config, but if you aren't able to do this, and want to put a web chat interface on an existing service then you probably want something like this.

A few caveats:
- This is abandonware-on-arrival. If you come up with a patch I may merge it, but I'm not providing support or responding to bugs
- The code is pretty hacked-together. It was a quick solution to a short-term problem. I've tried to comment it to explain what's going on, but that's about it
- XMPP is increasingly a wasteland. It wasn't really cool for chat when chat was all on MSN and AIM. It briefly got some attention when Google and Facebook made it an option, and then died off again. Google and Slack are dumping support this year. For non-chat applications it makes more sense to just talk direct to your service via websockets and probably always has.

If you want to run the code you'll need to go into the base directory and run:
```ant jar
java -cp libs/* xmpp.Bridge <YOUR SERVER NAME>```

If you want to deploy it to a real server and run it as a service (you shouldn't, obviously):
- Change libs/xmpp-websocket-proxy.service to the name of the server you want to proxy
- Create a directory on the server you want to deploy to
- Either change build.xml to use the name of the server and directory you want to deploy to, or copy all the files in `lib` to your server manually
- In the directory with all the files in run `sudo ./sudo-actions`. This will copy the service file into your services directory, set it to autostart on boot, and start it now.
- You'll also want to open up port 5280 for inbound connections, otherwise it isn't going to do anything very useful

Again, don't do this
