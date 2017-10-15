# Pinger
The utility monitors remote address reachibility. It doesn't matter if it's an internet address - like 8.8.8.8 - or a local one.

# Setup
For MySql connetion, one needs to download jdbc driver from the Oracle's website and copy the jar into the JRE's lib/ext folder.

# Configuration
Copy or rename config-example.xml to config.xml and put in you configuration values.

# Output
There are currently two outputs:
- a pipe delimited file and
- mysql database

# Visualising output
Any tool - Excel, PowerBI, R, Tableau - will do the job. I choose to go with Tableau. A setup dashboard is attached in this repository.
