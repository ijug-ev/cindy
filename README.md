# 👱🏻‍♀️ Cindy: iJUG's homebrewn event dispatcher ☕

*Cindy* is a Java-implemented RESTful Web Service for collecting events in [iCalender](https://www.rfc-editor.org/rfc/rfc5545.txt) format and publishing them on a Mastodon instance.


## Administrate

* `docker logs cindy` - Contains (at least) one entry per synchronization run.


## Deploy

* `docker run --name cindy -d -e CINDY_IP_PORT=7231 -e ... -P ghcr.io/ijug-ev/cindy`: Cindy will listen to requests on port 7231.
  - `CINDY_IP_PORT`: Cindy will listen to this IP port. The default is `8080`.
  -	`CINDY_MASTODON_HOST`: Cindy will publish events on this Mastodon host. There is no default.
  -	`CINDY_MASTODON_ACCESS_TOKEN`: Cindy uses this access token to log in to the Mastodon host. There is no default.
  -	`CINDY_DATA`: File system folder where Cindy persists internal data. For example, Cindy rembembers the instant when it last pulled calenders, so it understands which calender events are new/modified, and which are old. The default is `.`
  -	`CINDY_POLLING_SECONDS`: Time between two calendar polls. The default frequency is one minute, i. e. `60`.
  - `CINDY_CALENDAR_SOURCES`: Comma-separated list of download URLs of calendars to poll. There is no default.
  - `CINDY_TARGET_ZONE`: Timezone to use when formatting event message (e. g. "Europe/Berlin" will tell German local time in event message). Default is host's timezone.


## Build

`./build`, i. e.:
```bash
#!/bin/bash
mvn clean package
cp src/main/docker/Dockerfile target
pushd ./target
docker build -t cindy .
popd
docker run -it --rm -P cindy
```
