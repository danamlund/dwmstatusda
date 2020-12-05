/**
 * dwmstatusda
 *
 * Copyright (C) 2013 by Dan Amlund Thomsen <dan@danamlund.dk>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

/* dwmstatusda
 * 
 * Dan Amlund Thomsen
 * dan@danamlund.dk
 *
 * http://danamlund.dk/dwm_setup.html
 *
 * 2013-08-26: Version 1.0
 *
 * gcc -Os -Wall -pedantic -std=c99 dwmstatusda.c -lX11 -o dwmstatusda
 */

#define STATUS_MAX_LENGTH 512
#define CPUS 128

#define _BSD_SOURCE

#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <string.h>
#include <stdarg.h>
#include <unistd.h>
#include <ctype.h>

#include <X11/Xlib.h>

#define MIN(A,B) ((A) < (B) ? (A) : (B))

static Display *dpy;

const char *timeformat = "%a %d %b %H:%M";

unsigned long last_cpu_total[CPUS] = { 0 };
unsigned long last_cpu_idle[CPUS] = { 0 };

void setstatus(char *str) {
  XStoreName(dpy, DefaultRootWindow(dpy), str);
  XSync(dpy, False);
}

int snprintf0(char *str, int n, char *fmt, ...) {
  va_list fmtargs;
  char buf[256];
  int len;
  
  va_start(fmtargs, fmt);
  len = vsnprintf(buf, n, fmt, fmtargs);
  va_end(fmtargs);
  
  strncpy(str, buf, len);
  return len;
}

int fill_temp(char *str, int n, const char *sensor) { 
  FILE *sensors = popen("sensors", "r");
  if (NULL == sensors) {
    fprintf(stderr, "Could not open the 'sensors' command\n");
    exit(1);
  }

  char line[256];
  int sensor_len = strlen(sensor);
  while (NULL != fgets(line, 256, sensors)) {
    if (0 == strncmp(sensor, line, sensor_len)) {
      char reading[64];
      if (0 == sscanf(line + sensor_len + 1, "%s", reading)) {
        goto notfound;
      }
      pclose(sensors);
      int readinglen = strlen(reading);
      // remove unicode character and replace it with C or F
      reading[readinglen - 5] = reading[readinglen - 1]; 
      reading[readinglen - 4] = '\0';

      return snprintf0(str, n, "%3s", reading + 1);
    }
  }
 notfound:
  pclose(sensors);
  fprintf(stderr, "Could not find '%s' in sensors\n", sensor);
  exit(1);
}

int fill_mem_usage(char *str, int n) {
  FILE *meminfo = fopen("/proc/meminfo", "r");
  if (meminfo == NULL) {
    fprintf(stderr, "Could not open '/proc/meminfo'\n");
    exit(1);
  }

  int memtotal, memfree, buffers, cached, swaptotal, swapfree;
  char line[256];
  while (NULL != fgets(line, 256, meminfo)) {
    sscanf(line, "MemTotal: %d", &memtotal);
    sscanf(line, "MemFree: %d", &memfree);
    sscanf(line, "Buffers: %d", &buffers);
    sscanf(line, "Cached: %d", &cached);
    sscanf(line, "SwapTotal: %d", &swaptotal);
    sscanf(line, "SwapFree: %d", &swapfree);
  }
  fclose(meminfo);

  float mem_usage = 1.0 - (float) (memfree + buffers + cached) / memtotal;
  float swap_usage = 1.0 - (float) swapfree / swaptotal;

  int single_digit_mem_usage = MIN(9, (int) (mem_usage * 10));
  int single_digit_swap_usage = MIN(9, (int) (swap_usage * 10));

  return snprintf0(str, n, "%d%d", single_digit_mem_usage, 
                   single_digit_swap_usage);
}

int fill_cpu_usage(char *str, int n) { 
  char line[256], cpu_str[10];

  FILE *stat = fopen("/proc/stat", "r");
  if (NULL == stat) {
    fprintf(stderr, "Could not open '/proc/stat'\n");
    exit(1);
  }

  if (NULL == fgets(line, sizeof(line), stat)) {
    fclose(stat);
    fprintf(stderr, "Could not parse '/proc/stat'\n");
    exit(1);
  }
  unsigned long cpu_total[CPUS];
  unsigned long cpu_idle[CPUS];
  int cpus = -1;
  for (int cpu = 0; cpu < CPUS; cpu++) {
    if (NULL == fgets(line, sizeof(line), stat)) {
      fclose(stat);
      fprintf(stderr, "Could not find all %d spu cores in '/proc/stat'\n",
              CPUS);
      exit(1);
    }
    if (0 != strncmp(line, "cpu", 3)) {
      cpus = cpu;
      break;
    }
    unsigned long user, nice, system, idle;
    if (5 != sscanf(line, "%s %lu %lu %lu %lu",
                    cpu_str, &user, &nice, &system, &idle)) {
        cpus = cpu;
        break;
    }
    cpu_total[cpu] = user + nice + system;
    cpu_idle[cpu] = idle;
  }  
  fclose(stat);

  if (0 != last_cpu_total[0] && 0 != last_cpu_idle[0]) {
    for (int cpu = 0; cpu < cpus; cpu++) {
      float delta_total = cpu_total[cpu] - last_cpu_total[cpu];
      float delta_idle = cpu_idle[cpu] - last_cpu_idle[cpu];
      float cpu_usage = delta_total / (delta_total + delta_idle);
      int single_digit_cpu_usage = (int) (cpu_usage * 10);
      if (single_digit_cpu_usage < 0) {
        single_digit_cpu_usage = 0;
      }
      if (single_digit_cpu_usage > 9) {
        single_digit_cpu_usage = 9;
      }
      str[cpu] = '0' + single_digit_cpu_usage;
    }
  } else {
    for (int cpu = 0; cpu < cpus; cpu++) {
      str[cpu] = '0';
    }
  }

  for(int cpu = 0; cpu < cpus; cpu++) {
    last_cpu_total[cpu] = cpu_total[cpu];
    last_cpu_idle[cpu] = cpu_idle[cpu];
  }

  return cpus;
}

int fill_unread_mail(char *str, int n) { 
  FILE *f = popen("notmuch count tag:inbox", "r");
  if (f == NULL) {
    fprintf(stderr, "Could not open the 'notmuch' command\n");
    exit(1);
  }
  int unread_mail;
  if (0 == fscanf(f, "%d", &unread_mail)) {
    pclose(f);
    fprintf(stderr, "Could not parse notmuch output\n");
    exit(1);
  }
  pclose(f);
  str[0] = unread_mail == 0 ? ' ' : 'M';
  return 1;
}

int fill_date(char *str, int n) {
  struct tm *timtm;
  time_t tim = time(NULL);

  timtm = localtime(&tim);
  if (timtm == NULL) {
    fprintf(stderr, "Could not get localtime()\n");
    exit(1);
  }

  int wrote = strftime(str, n, timeformat, timtm);
  return wrote == 0 ? -1 : wrote;
}

char pct(float fraction) {
  int frac = (int) (fraction * 10);
  return frac >= 10 ? '9' : '0' + frac;
}

inline void ignore_result(char* ignored) { }

// Takes ~2 seconds to run
int fill_gcpct(char *str, int n) {
  FILE *f = popen("jvm-stats -c1", "r");

  int count = 0;

  char line[1024];
  if (NULL != f && NULL != fgets(line, sizeof(line), f)) {
    for (int i = 0; line[i] != '\n'; i++) {
      if (i >= n) {
        str[count] = '+';
        break;
      } else {
        str[count] = line[i];
      }
      count++;
    }
  }
  pclose(f);
  str[count] = '\0';
  return count;
}

// Warning: takes 1 second to run
int fill_ioutil(char *str, int n) {
  if (n == 0) return 0;
  FILE *f = popen("iostat -x 1 2", "r");
  if (NULL == f) return 0;

  char line[1024];
  int skips = 2;
  while (NULL != fgets(line, sizeof(line), f)) {
    if (strncmp("Device", line, strlen("Device")) == 0)
      skips--;
    if (skips == 0) {
      break;
    }
  }

  if (NULL == fgets(line, sizeof(line), f)) return 0;

  float maxUtil = 0.0;
  while (NULL != fgets(line, sizeof(line), f)) {
    char *last = rindex(line, ' ');
    if (NULL != last) {
      float util = atof(last);
      if (util > maxUtil)
        maxUtil = util;
    }
  }
  pclose(f);

  if (maxUtil > 100.0) 
    maxUtil = 100.0;

  str[0] = pct(maxUtil / 100.0);
  return 1;
}


int main(int argc, char **args) {
  dpy = XOpenDisplay(NULL);
  if (NULL == dpy) {
    return 1;
  }

  char temp[5], memusage[5], cpuusage[64], unreadmail[2], date[32], gcpct[128], ioutil[2];
  char buf[STATUS_MAX_LENGTH];

  fill_cpu_usage(buf, sizeof(buf));
  sleep(1);

  int terminater = 0;
  terminater = fill_temp(temp, sizeof(temp), "temp1");
  temp[terminater] = '\0';
  terminater = fill_mem_usage(memusage, sizeof(memusage));
  memusage[terminater] = '\0';
  terminater = fill_cpu_usage(cpuusage, sizeof(cpuusage));
  cpuusage[terminater] = '\0';
  terminater = fill_unread_mail(unreadmail, sizeof(unreadmail));
  unreadmail[terminater] = '\0';
  terminater = fill_date(date, sizeof(date));
  date[terminater] = '\0';
  /* terminater = fill_gcpct(gcpct, sizeof(gcpct)); */
  /* gcpct[terminater] = '\0'; */
  gcpct[0] = '\0';
  terminater = fill_ioutil(ioutil, sizeof(ioutil));
  ioutil[terminater] = '\0';

  unsigned long sleeps = 0;
  while (1) {
    fill_temp(temp, sizeof(temp), "temp1");
    fill_mem_usage(memusage, sizeof(memusage));
    fill_cpu_usage(cpuusage, sizeof(cpuusage));
    if (sleeps % 20 == 0) {
      fill_date(date, sizeof(date));
      fill_unread_mail(unreadmail, sizeof(unreadmail));
    }
    if (sleeps % 10 == 0) {
      /* terminater = fill_gcpct(gcpct, sizeof(gcpct)); */
      /* gcpct[terminater] = '\0'; */
    }
    /* sprintf(buf, "%s %s %s%s %s %s %s", gcpct, temp, memusage, ioutil, cpuusage, unreadmail, date); */
    sprintf(buf, "%s %s %s%s %s %s", gcpct, temp, memusage, ioutil, cpuusage, unreadmail);
    /* printf("_%s_%s_%s_%s_%s_%s_\n", gcpct, temp, memusage, ioutil, cpuusage, unreadmail); */
    setstatus(buf);
    if (argc >= 2) {
        printf("%s\n", buf);
        for (int i = 0; buf[i] != '\0'; i++) {
            printf("%02X ", buf[i]);
        }
        printf("\n");
    }
    sleep(1);
    fill_ioutil(ioutil, sizeof(ioutil)); // sleeps 1
    sleeps += 2;
  }
  
  return 0;
}
