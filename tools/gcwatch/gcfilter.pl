#!/usr/bin/perl
# The writing end of gcwatch: takes the log stream on stdin and keeps two files.
#
# It exists because doing this with a shell redirect got three things wrong, all quietly:
#
#   1. The flood signature was in the noise list. "Incoming Driver Connection" is the one line
#      that names the fault we are hunting, and it was being discarded as boilerplate because
#      there are thousands of them. Thousands of them IS the finding. They are counted here and
#      a rate line goes into the events file, so the signal survives without the volume.
#   2. A shell redirect is bound when the pipeline starts, so the midnight rollover never
#      happened: the loop reassigned the variable and the stream carried on writing to
#      yesterday's file. One run left a 1.1 GB events-<yesterday>.log still growing the next
#      afternoon. The files are opened here, by this process, and reopened when the date turns.
#   3. Only the raw file had a size cap. The events file had none, which is how it reached
#      1.1 GB. Both are capped now.
#
# Reads GCW_DIR and GCW_NOISE from the environment. Run with --selftest to prove it works.
use strict;
use warnings;
use POSIX qw(strftime);

my $dir   = $ENV{GCW_DIR}   or die "GCW_DIR not set\n";
my $noise = qr/$ENV{GCW_NOISE}/i;
my $flood = qr/Incoming (?:Driver )?Connection/i;
my $cap   = ($ENV{GCW_CAP} || 200 * 1024 * 1024);
my $every = ($ENV{GCW_EVERY} || 10);

my ($day, $RAW, $EV) = ("");
my ($n, $peer, $mark) = (0, "", time);

sub openday {
    my $d = strftime("%F", localtime);
    return if $d eq $day;
    $day = $d;
    close $RAW if $RAW;
    close $EV  if $EV;
    open $RAW, ">>", "$dir/raw-$day.log"    or die "raw: $!\n";
    open $EV,  ">>", "$dir/events-$day.log" or die "events: $!\n";
    select((select($RAW), $| = 1)[0]);
    select((select($EV),  $| = 1)[0]);
}

# Rotate rather than trim: an append handle cannot tail-truncate its own file without a window
# where lines are lost. Renaming keeps one previous generation and bounds the pair at twice the cap.
sub rotate {
    for my $pair (["raw", \$RAW], ["events", \$EV]) {
        my ($name, $ref) = @$pair;
        my $f = "$dir/$name-$day.log";
        my $sz = -s $f;
        next unless defined $sz && $sz > $cap;
        close $$ref;
        rename $f, "$f.prev";
        open $$ref, ">>", $f or die "$name: $!\n";
        select((select($$ref), $| = 1)[0]);
    }
}

sub flush_count {
    return unless $n;
    printf $EV "%s  [%d driver connections in %ds, %.0f/sec, %s]\n",
        strftime("%F %T", localtime), $n, $every, $n / $every,
        ($peer ne "" ? $peer : "peer unknown");
    $n = 0;
    $peer = "";
}

openday();
while (my $line = <STDIN>) {
    print $RAW $line;
    if ($line =~ $flood) {
        $n++;
        $peer = $& if $line =~ /driver\.peer\[\d+\]/;
    } elsif ($line !~ $noise) {
        print $EV $line;
    }
    my $now = time;
    if ($now - $mark >= $every) {
        $mark = $now;
        flush_count();
        openday();
        rotate();
    }
}
flush_count();
