# CodeverseMaintenance

Maintenance and pre-launch gating for a Velocity network that accepts cracked,
Bedrock and premium players at the same time.

Closes the network to everyone except an allowlist, renders a server list entry
that greets returning players by name, holds people in the waiting room rather
than kicking them for short windows, and announces to Discord in each reader's
own timezone.

## Why the gate has two halves

At login an identity is only worth judging if something verified it. Mojang
verified a premium account and Floodgate verified a Bedrock one, so those can be
allowed or refused immediately. A cracked connection has proven nothing: the
username is a claim anybody can make, which is the whole reason
[CodeverseAuth](https://github.com/CodeVerseHub-Minecraft/CodeverseAuth) exists.

So an allowlist checked only at login would be spoofable by anyone who types a
staff member's name, and refusing cracked connections outright would lock out
cracked staff.

Limbo therefore stays reachable while the network is closed, because limbo is
where people prove who they are. The gate that actually holds is the transfer
out of limbo, by which point the internal id is known. That same gate is how
players are held in the waiting room, since both are the same question: may this
person leave limbo right now.

## The allowlist, and the way back in

Three mechanisms, in the order they are checked.

**Break glass uuids** in config are raw premium uuids. They need no database, no
authentication plugin and no permission system, because Mojang verified those
accounts during the handshake. This exists because the situation that most often
calls for maintenance is a database migration, and an allowlist that only works
when the database is up fails exactly when it is needed. Set at least one, or
the plugin warns at startup that a database outage would lock you out of your
own network.

**The explicit list** is internal ids, so an allowance follows a person across
their linked Java and Bedrock accounts rather than applying to one of them.

**The bypass permission** is last and cannot be relied on alone. CodeverseAuth
bars `CRACKED` accounts from elevated permissions in code, independent of any
permission setup, so a member of staff who plays cracked and has not linked
Discord can never hold the node. For them the explicit list is the only
mechanism that can work.

## Two modes

`MAINTENANCE` is short and says when it is coming back. `PRE_LAUNCH` is long and
says what is coming. They are separate because collapsing them means either
telling someone waiting on a five minute restart that the network opens in
March, or telling someone who arrived a month early to try again shortly.

## Times

Every player facing time is relative: "back in 44m", never a clock reading. A
server list ping cannot tell us where the person asking is, and this community
spans enough timezones that an absolute time would be wrong for most of them.

Discord announcements use Discord's own timestamp markup, which each reader's
client renders in their own timezone. That is the only way to be correct without
knowing where anyone is.

## Commands

`/maintenance` on its own draws the status view, with clickable actions. Every
action is also reachable by typing the whole command, so nothing is only
possible through the interface.

```
/maintenance status
/maintenance on <reason>
/maintenance on <duration> <reason>
/maintenance prelaunch <reason>
/maintenance off
/maintenance schedule <when> [duration] <reason>
/maintenance unschedule
/maintenance server <name> on|off
/maintenance allow add|remove <player>
/maintenance webhook test
```

Durations are `30m`, `2h`, `1d`, or combinations like `1h30m`. A bare number is
refused rather than assumed to be minutes.

A scheduled moment may be relative, which is timezone free and usually what is
meant, or an absolute instant carrying an explicit zone such as
`2026-03-15T18:00:00Z`. A bare local time is refused: written without a zone it
is ambiguous exactly once, on the day it matters.

The status view redraws only in response to something you did. Nothing redraws
on a timer, because flushing someone's chat while they are reading it is worse
than a slightly stale view.

## Requirements, and one thing to know about versions

Velocity 4, Java 25, and CodeverseAuth on the proxy. The dependency is not
optional: this plugin compiles against the shared API and never ships it, so
without Auth those classes exist nowhere on the proxy.

**The plugin that bundles the API decides the network's effective API version.**
Auth ships the shared contract, and this plugin uses whatever Auth provides, in
the same way a Paper plugin cannot call an API method the running server does
not have. A release that adds to the contract has to reach Auth before any
consumer can use the addition, so the deploy order is CodeverseAPI, then Auth,
then everything else. Getting this wrong produces a `NoClassDefFoundError` at
startup naming the missing contract class.

This plugin needs **CodeverseAPI 0.3.0** or newer, which means **CodeverseAuth
0.2.3** or newer.

## Control interface

Disabled by default. When enabled it lets a Discord bot see and change
maintenance without a client.

```
GET    /v1/status
POST   /v1/maintenance    {"mode":"MAINTENANCE","reason":"...","durationSeconds":1800}
DELETE /v1/maintenance
```

Requests carry an HMAC-SHA256 signature over method, path, timestamp, nonce and
a digest of the body, the same shape CodeverseAuth uses. The nonce is load
bearing: without it two identical requests in the same second produce the same
signature and the second is refused as a replay, which would break a bot polling
status.

The address allowlist is checked before any credential, so a token lifted from a
log is useless from elsewhere. Every refusal returns the same response, so a
caller cannot distinguish a wrong address from a wrong signature from a path
that does not exist. Status carries counts rather than contents, because an
endpoint that lists who may pass is an endpoint that says who to impersonate.

Firewall the port to the same addresses as well. The allowlist and the firewall
then fail independently.

## For other plugins

The maintenance state is published through the shared API, so nothing has to
guess:

```java
CodeverseApiProvider.get().maintenance().ifPresent(maintenance -> {
    if (maintenance.isActive()) {
        return;
    }
    maintenance.upcoming()
            .flatMap(window -> window.startsInAt(Instant.now()))
            .filter(until -> until.toMinutes() < 5)
            .ifPresent(until -> log.info("Not starting: maintenance in {}", until));
});
```

A minigame framework should check `upcoming()` before starting a round that
would be interrupted, and a queue should check `isClosed(server)` before holding
players for a server that is down.

## Building

```bash
./gradlew clean build
```

## What has been verified by execution

Booted on a real Velocity 4.0.0 proxy alongside CodeverseAuth against a real
MariaDB and Redis. The server list entry was checked by pinging the proxy over
the Minecraft protocol in all three states, confirming the description, the
version slot label, the decoy player count and the live countdown. Windows were
opened and closed from the console, the state file was confirmed on disk between
each change, and the audit log recorded every toggle with its source.

The control interface was driven over real sockets end to end: unsigned, wrongly
signed, stale and replayed requests refused; two identical requests in the same
second both accepted; an unknown path refused indistinguishably from a bad
signature; and a window opened remotely then confirmed by pinging the proxy and
seeing the server list entry a stranger would get.

## What has not

No real player has been gated, held in limbo or released, because that needs
clients. Redis propagation between two proxies is designed but not implemented,
so this is single proxy for now. The Discord webhook has been exercised through
its test command but no live announcement has been posted to a real channel, and
no scheduled window has been left to activate on its own.

## License

MIT. See [LICENSE](LICENSE).

## About

This project is maintained by the CodeVerseHub-Minecraft Subteam, which works
alongside the wider CodeVerseHub community but is a separate team. CodeVerseHub
is not responsible for these projects.
