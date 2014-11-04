package wtrs_etl;

use strict;
#use warnings;
use Carp;

die("WTRS_SPOOL_STEWED is not set") unless defined($ENV{WTRS_SPOOL_STEWED});
my $d = $ENV{WTRS_SPOOL_STEWED};

# we *know* that the input files are sorted by date.  (because sorting by sk === sorting by date).
# therefore we're sure to get the most recent sk for any particular nk.
# (in the case of type 2 scd)

sub slurp {
	my $filename = $_[0];
	my %h;
	open(my $in,"<$filename") || croak("open($filename)");;
	while(<$in>) {
		my ($sk,$nk) = split(/\|/);
		$h{$nk} = $sk;
	}
	close($in);
	#print STDERR %h,"\n"; 
	\%h;
}

# the real natural key for dimension_ip is (ip,wtid)
# let's key our hash with ip_wtid
sub slurp_ip {
	my $filename = "$d/dimension_ip.txt";
	my %h;
	open(my $in,"<$filename") || croak("open($filename)");;
	while(<$in>) {
		my ($sk,$ip,$ip_raw,$wtid) = split(/\|/);
		my $nk = $ip . "_" . $wtid;
		$h{$nk} = $sk;
	}
	close($in);
	#print STDERR %h,"\n"; 
	\%h;
}

sub slurp_service {
	slurp "$d/dimension_service.txt";
}

sub slurp_function {
	slurp "$d/dimension_function.txt";
}

# dimension_node has a compound natural key (nodeid,wtid)
# key our hash with nodeid + '_' + wtid
sub slurp_node {
	my $filename = "$d/dimension_node.txt";
	my %h;
	open(my $in,"<$filename") || croak("open($filename)");;
	while(<$in>) {
		my ($sk,$nodeid,$os,$ip,$ip_raw,$mac,$wtid) = split(/\|/);
		my $nk = $nodeid . "_" . $wtid;
		$h{$nk} = $sk;
	}
	close($in);
	#print STDERR %h,"\n"; 
	\%h;
}

sub slurp_storage {
	slurp "$d/dimension_storage.txt";
}

sub slurp_oid {
	my $h = slurp "$d/dimension_oid.txt";

	# FIXME: the wt_oid_alias is truncated to $N chars by $WHO to create the filename
	#$h->{hrStorageAllocation} = $h->{hrStorageAllocationUnits};
	#$h->{mem-noncomputationa} = $h->{mem-noncomputational};
	#print STDERR "\n\n  hrStorageAllocationUnits=",$h->{hrStorageAllocationUnits},"\n\n";

	foreach my $alias (sort (keys %{$h})) {
		my $shortalias = substr($alias,0,19);
		$h->{$shortalias} = $h->{$alias};
	}

	$h;
}

# dimension_interface natural key is (wtid,nodeid,iflabel)
# key our hash with wtid + "/" + nodeid + "/" + iflabel
sub slurp_interface {
	my $filename = "$d/dimension_interface.txt";
	my %h;
	open(my $in,"<$filename") || croak("open($filename)");;
	while(<$in>) {
		my @a = split(/\|/);

		my $sk = $a[0];
		my $wtid = $a[1];
		my $nodeid = $a[2];
		my $iflabel = $a[25];

		my $nk = $wtid . "/" . $nodeid . "/" . $iflabel;

		$h{$nk} = $sk;
	}
	close($in);
	#print STDERR %h,"\n"; 
	\%h;
}
