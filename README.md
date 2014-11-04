WTRS stands for WatchTower Reporting System.
I built WTRS as a data warehouse to contain historical data from, and
provide a reporting capability to WatchTower, a Network Management System.

It was built with a mix of Java, Perl, bash, and SQL.
Third party tools were Postgres, Apache httpd, and Crystal Reports.

Included here are the more interesting parts of the system;
I've removed all the build, deploy, maintenance, and test bits.

The best parts are:

* client-rrdreader-src

<blockquote>
  The RRDReader extracts historical data from native binary files on
  a WatchTower client system and delivers that data to the WTRS.
</blockquote>

* server-etl

<blockquote>
  The ETL (Extract, Transform, Load) process takes data provided by
  the RRDReader along with some metadata from the client and loads
  it into the data warehouse after scrubbing, and renormalizing.
</blockquote>
